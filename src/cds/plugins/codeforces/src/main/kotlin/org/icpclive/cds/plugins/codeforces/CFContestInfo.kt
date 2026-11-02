package org.icpclive.cds.plugins.codeforces

import org.icpclive.cds.api.*
import org.icpclive.cds.plugins.codeforces.api.data.*
import org.icpclive.cds.plugins.codeforces.api.results.CFStandings
import kotlin.math.ceil
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.encodeToString
import kotlin.time.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes


private val HACKS_PROBLEM_ID = "hacks".toProblemId()

internal class CFContestInfo(private val includeUnofficial: Boolean = false) {
    private val submissionHistory = mutableMapOf<Long, CFSubmission>()
    private var contestLength: Duration = 5.hours
    private var freezeDuration: Duration? = null
    private var startTime: Instant = Instant.fromEpochMilliseconds(0)
    var status: ContestStatus = ContestStatus.BEFORE()
        private set
    private val problems = mutableListOf<ProblemInfo>()
    private var standingsMetadata: String = "{}"
    private val participants = linkedMapOf<TeamId, TeamInfo>()
    private val officialRejectedAttempts = mutableMapOf<Pair<TeamId, String>, Int>()
    private var contestType: CFContestType = CFContestType.ICPC
    private var penaltyPerWrongAttempt: Duration = 20.minutes
    private var penaltyContestId: Int? = null
    private var learnedPenaltyMinutes: Int? = null
    private var name: String = ""

    private fun updateContestInfo(contest: CFContest) {
        name = contest.name
        contestType = contest.type
        contestLength = contest.duration!!
        startTime = contest.startTime
            ?: Instant.DISTANT_FUTURE
        status = when (contest.phase) {
            CFContestPhase.BEFORE -> ContestStatus.BEFORE(scheduledStartAt = startTime.takeUnless{ it.isDistantFuture })
            CFContestPhase.CODING, CFContestPhase.PENDING_SYSTEM_TEST, CFContestPhase.SYSTEM_TEST -> ContestStatus.RUNNING(startedAt = startTime, frozenAt = null)
            else -> ContestStatus.OVER(startedAt = startTime, finishedAt = startTime + contestLength, frozenAt = null)
        }
        freezeDuration = contest.freezeDuration
    }

    fun updateStandings(standings: CFStandings) {
        standingsMetadata = buildJsonObject {
            put("contest", Json.encodeToJsonElement(standings.contest))
            put("problems", Json.encodeToJsonElement(standings.problems))
        }.toString()
        updateContestInfo(standings.contest)
        if (contestType == CFContestType.ICPC) {
            if (penaltyContestId != standings.contest.id) {
                penaltyContestId = standings.contest.id
                learnedPenaltyMinutes = null
            }
            // Row totals are present in the public API, unlike per-problem penalties.
            val candidates = standings.rows.asSequence()
                .filter { it.party.participantType == CFPartyParticipantType.CONTESTANT }
                .mapNotNull { row ->
                    val solved = row.problemResults.filter { it.points > 0 }
                    if (row.points != solved.size.toDouble() || solved.any { it.bestSubmissionTimeSeconds == null || it.bestSubmissionTimeSeconds <= 0 || it.rejectedAttemptCount < 0 }) return@mapNotNull null
                    val wrong = solved.sumOf { it.rejectedAttemptCount }
                    val extra = row.penalty.toLong() - solved.sumOf { it.bestSubmissionTimeSeconds!! / 60 }
                    if (wrong > 0 && extra >= 0 && extra % wrong == 0L) (extra / wrong).toInt() else null
                }.toSet()
            if (learnedPenaltyMinutes == null && candidates.size == 1) learnedPenaltyMinutes = candidates.single()
            // Missing/conflicting evidence never discards a reliable learned rule.
            penaltyPerWrongAttempt = (learnedPenaltyMinutes
                ?: if (standings.contest.id < 100000) 10 else 20).minutes
        }
        problems.clear()
        for ((id, problem) in standings.problems.withIndex()) {
            val problemInfo = ProblemInfo(
                id = problem.index.toProblemId(),
                displayName = if (contestType == CFContestType.BLITZ) problem.points!!.toInt().toString() else problem.index,
                fullName = problem.name!!,
                ordinal = id,
                minScore = if (problem.points != null) 0.0 else null,
                maxScore = problem.points,
                scoreMergeMode = when (contestType) {
                    CFContestType.CF -> ScoreMergeMode.LAST_OK
                    CFContestType.ICPC, CFContestType.BLITZ -> null
                    CFContestType.IOI -> ScoreMergeMode.MAX_TOTAL
                },
                weight = if (contestType == CFContestType.BLITZ) problem.points!!.toInt() else 1,
                ftsMode = if (contestType == CFContestType.BLITZ) FtsMode.Hidden else FtsMode.Auto
            )
            problems.add(problemInfo)
        }
        if (contestType == CFContestType.CF) {
            val hacksInfo = ProblemInfo(
                id = HACKS_PROBLEM_ID,
                displayName = "*",
                fullName = "Hacks",
                ordinal = -1,
                minScore = null,
                maxScore = null,
                scoreMergeMode = ScoreMergeMode.SUM,
                ftsMode = FtsMode.Hidden
            )
            problems.add(hacksInfo)
        }
        participants.clear()
        officialRejectedAttempts.clear()
        standings.rows.filter { it.party.participantType == CFPartyParticipantType.CONTESTANT }.forEach { row ->
            row.problemResults.forEachIndexed { index, result ->
                val problem = standings.problems.getOrNull(index) ?: return@forEachIndexed
                officialRejectedAttempts[getTeamCdsId(row.party).toTeamId() to problem.index] = result.rejectedAttemptCount
            }
        }
        standings.rows.forEach { addParticipant(it.party) }
    }

    private fun included(party: CFParty): Boolean =
        (party.participantType == CFPartyParticipantType.CONTESTANT ||
            (includeUnofficial && party.participantType == CFPartyParticipantType.OUT_OF_COMPETITION))

    private fun addParticipant(party: CFParty) {
        if (!included(party)) return
        val id = getTeamCdsId(party).toTeamId()
        if (id in participants) return
        val displayName = party.teamName ?: party.members.first().let { it.name ?: it.handle }
        val team = TeamInfo(
            id = id, fullName = displayName, displayName = displayName,
            groups = emptyList(), hashTag = null, medias = emptyMap(), organizationId = null,
            isHidden = false, isOutOfContest = party.participantType != CFPartyParticipantType.CONTESTANT,
            customFields = mapOf("codeforces.party" to Json.encodeToString(party), "codeforces.participantType" to party.participantType.name)
        )
        participants[id] = team
    }

    private val CFSubmission.processedVerdict
        get() = when (verdict) {
            CFSubmissionVerdict.FAILED -> Verdict.Fail
            CFSubmissionVerdict.OK -> Verdict.Accepted
            CFSubmissionVerdict.PARTIAL -> null
            CFSubmissionVerdict.COMPILATION_ERROR -> Verdict.CompilationError
            CFSubmissionVerdict.RUNTIME_ERROR -> Verdict.RuntimeError
            CFSubmissionVerdict.WRONG_ANSWER -> Verdict.WrongAnswer
            CFSubmissionVerdict.PRESENTATION_ERROR -> Verdict.PresentationError
            CFSubmissionVerdict.TIME_LIMIT_EXCEEDED -> Verdict.TimeLimitExceeded
            CFSubmissionVerdict.MEMORY_LIMIT_EXCEEDED -> Verdict.MemoryLimitExceeded
            CFSubmissionVerdict.IDLENESS_LIMIT_EXCEEDED -> Verdict.IdlenessLimitExceeded
            CFSubmissionVerdict.SECURITY_VIOLATED -> Verdict.SecurityViolation
            CFSubmissionVerdict.CRASHED -> Verdict.Fail
            CFSubmissionVerdict.INPUT_PREPARATION_CRASHED -> Verdict.Fail
            CFSubmissionVerdict.CHALLENGED -> Verdict.Challenged
            CFSubmissionVerdict.SKIPPED -> Verdict.Ignored
            CFSubmissionVerdict.TESTING, CFSubmissionVerdict.SUBMITTED -> null
            CFSubmissionVerdict.REJECTED -> Verdict.Rejected
            null -> null
        }?.let {
            if (contestType == CFContestType.CF && passedTestCount == 0 && it.isAddingPenalty && !it.isAccepted) {
                Verdict.lookup(it.shortName, isAddingPenalty = false, isAccepted = false)
            } else {
                it
            }
        }

    private fun submissionToResult(submission: CFSubmission, wrongAttempts: Int, suppressPenalty: Boolean = false): RunResult? {
        if (submission.verdict == null || submission.verdict == CFSubmissionVerdict.TESTING || submission.verdict == CFSubmissionVerdict.SUBMITTED) return null
        return when (contestType) {
            CFContestType.ICPC -> {
                val verdict = submission.processedVerdict?.let {
                    if (suppressPenalty && it.isAddingPenalty) Verdict.lookup(it.shortName, isAddingPenalty = false, isAccepted = false) else it
                }
                verdict?.toICPCRunResult()
            }

            CFContestType.BLITZ -> {
                val prelim = submission.processedVerdict?.toICPCRunResult()
                if (prelim?.verdict == Verdict.Accepted && submission.points == null) {
                    Verdict.Ignored.toICPCRunResult()
                } else {
                    prelim
                }
            }

            CFContestType.IOI -> {
                // TODO: is CFSubmissionVerdict.SKIPPED not wrong
                val isWrong = submission.verdict !in listOf(
                    CFSubmissionVerdict.OK,
                    CFSubmissionVerdict.CHALLENGED,
                    CFSubmissionVerdict.PARTIAL
                )
                val score = submission.points?.takeIf { !isWrong } ?: 0.0
                RunResult.IOI(
                    score = listOf(score),
                    wrongVerdict = submission.processedVerdict.takeIf { isWrong },
                )
            }

            CFContestType.CF -> {
                // TODO: this should come from API
                val maxScore = problems.firstOrNull { it.id.value == submission.problem.index }?.maxScore ?: submission.problem.points ?: error("Missing problem points")
                val isWrong = submission.verdict != CFSubmissionVerdict.OK
                val score = if (!isWrong) {
                    submission.points?.takeIf { it > 0 } ?: maxOf(
                        maxScore * 3 / 10,
                        ceil(
                            maxScore - submission.relativeTimeSeconds.inWholeMinutes * getProblemLooseScorePerMinute(
                                maxScore, contestLength.inWholeMinutes
                            ) - 50 * wrongAttempts
                        )
                    )
                } else {
                    0.0
                }
                RunResult.IOI(
                    score = listOf(score),
                    wrongVerdict = submission.processedVerdict.takeIf { isWrong },
                )
            }
        }
    }

    private fun getProblemLooseScorePerMinute(initialScore: Double, duration: Long): Double {
        val roundedDuration = maxOf(1, duration / 30) * 30
        return (initialScore - 0.52 * initialScore) / roundedDuration
    }

    fun parseSubmissions(latestSubmissions: List<CFSubmission>): List<RunInfo> {
        latestSubmissions.forEach { submissionHistory[it.id] = it }
        val submissions = submissionHistory.values.filter { included(it.author) }
        submissions.forEach { addParticipant(it.author) }
        val problemTestsCount = submissions.groupBy { it.problem.index }.mapValues { (_, v) ->
            v.maxOf { submit -> submit.passedTestCount + if (submit.verdict == CFSubmissionVerdict.OK) 0 else 1 }
        }
        return submissions.reversed().asSequence()
            .filter { included(it.author) }
            .groupBy { it.author to it.problem }
            .mapValues { (_, submissions) ->
                var wrongs = 0
                submissions.sortedBy { it.id }.map {
                    val problemId = it.problem.index.toProblemId()
                    val problemTests = problemTestsCount[it.problem.index]
                    val officialWrongAttempts = officialRejectedAttempts[getTeamCdsId(it.author).toTeamId() to it.problem.index]
                    val suppressPenalty = officialWrongAttempts != null && wrongs >= officialWrongAttempts
                    val result = submissionToResult(it, wrongs, suppressPenalty)
                    val run = RunInfo(
                        id = it.id.toRunId(),
                        result = result ?: RunResult.InProgress(if (problemTests == null) 0.0 else it.passedTestCount.toDouble() / problemTests),
                        problemId = problemId,
                        teamId = getTeamCdsId(it.author).toTeamId(),
                        time = it.relativeTimeSeconds,
                        languageId = it.programmingLanguage.toLanguageId(),
                        customFields = mapOf("codeforces.submission" to Json.encodeToString(it))
                    )
                    wrongs += if (it.processedVerdict?.isAddingPenalty == true) 1 else 0
                    run
                }
            }.values.flatten()
    }

    fun parseHacks(hacks: List<CFHack>): List<RunInfo> {
        return buildList {
            for (hack in hacks) {
                if (hack.creationTimeSeconds - startTime > contestLength) continue
                addParticipant(hack.hacker)
                addParticipant(hack.defender)
                if (included(hack.hacker)) {
                    add(
                        RunInfo(
                            id = "hack-attack-${hack.id}".toRunId(),
                            result = when (hack.verdict) {
                                CFHackVerdict.HACK_SUCCESSFUL -> RunResult.IOI(
                                    score = listOf(100.0),
                                )

                                CFHackVerdict.HACK_UNSUCCESSFUL -> RunResult.IOI(
                                    score = listOf(-50.0),
                                )

                                CFHackVerdict.TESTING -> RunResult.InProgress(0.0)
                                else -> RunResult.IOI(
                                    score = emptyList(),
                                    wrongVerdict = Verdict.CompilationError,
                                )
                            },
                            problemId = HACKS_PROBLEM_ID,
                            teamId = getTeamCdsId(hack.hacker).toTeamId(),
                            time = hack.creationTimeSeconds - startTime,
                            languageId = null,
                            customFields = mapOf("codeforces.hack" to Json.encodeToString(hack))
                        )
                    )
                }
                if (included(hack.defender)) {
                    add(
                        RunInfo(
                            id = "hack-defend-${hack.id}".toRunId(),
                            result = RunResult.IOI(
                                score = listOf(0.0),
                                wrongVerdict = if (hack.verdict == CFHackVerdict.HACK_SUCCESSFUL) null else Verdict.Accepted,
                            ),
                            isHidden = hack.verdict != CFHackVerdict.HACK_SUCCESSFUL,
                            problemId = hack.problem.index.toProblemId(),
                            teamId = getTeamCdsId(hack.defender).toTeamId(),
                            time = hack.creationTimeSeconds - startTime,
                            languageId = null,
                            customFields = mapOf("codeforces.hack" to Json.encodeToString(hack))
                        )
                    )
                }
            }
        }
    }

    fun toApi() = ContestInfo(
        name = name,
        status = status,
        resultType = when (contestType) {
            CFContestType.CF, CFContestType.IOI -> ContestResultType.IOI
            CFContestType.ICPC, CFContestType.BLITZ -> ContestResultType.ICPC
        },
        contestLength = contestLength,
        freezeTime = freezeDuration?.let { contestLength - it },
        problemList = problems.sortedBy { it.ordinal },
        teamList = participants.values.sortedBy { it.id.value },
        groupList = emptyList(),
        penaltyRoundingMode = when (contestType) {
            CFContestType.CF -> PenaltyRoundingMode.ZERO
            CFContestType.IOI -> PenaltyRoundingMode.ZERO
            CFContestType.BLITZ -> PenaltyRoundingMode.ZERO
            CFContestType.ICPC -> PenaltyRoundingMode.EACH_SUBMISSION_DOWN_TO_MINUTE
        },
        penaltyPerWrongAttempt = penaltyPerWrongAttempt,
        organizationList = emptyList(),
        languagesList = emptyList(),
        customFields = mapOf("codeforces.standings" to standingsMetadata),
    )

    companion object {
        fun getTeamCdsId(party: CFParty): String {
            return party.teamId?.let { "team:${it}" } ?: party.members[0].handle
        }
    }
}
