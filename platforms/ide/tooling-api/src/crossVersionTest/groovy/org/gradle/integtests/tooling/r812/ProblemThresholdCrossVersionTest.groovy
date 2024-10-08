/*
 * Copyright 2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.integtests.tooling.r812

import org.gradle.api.problems.internal.ProblemSummarizer
import org.gradle.integtests.tooling.fixture.TargetGradleVersion
import org.gradle.integtests.tooling.fixture.ToolingApiSpecification
import org.gradle.integtests.tooling.fixture.ToolingApiVersion
import org.gradle.integtests.tooling.r85.CustomModel
import org.gradle.tooling.events.problems.ProblemsSummariesEvent
import org.gradle.tooling.events.problems.SingleProblemEvent

import static org.gradle.api.problems.ReportingScript.getProblemReportingScript
import static org.gradle.api.problems.internal.ProblemSummarizer.THRESHOLD
import static org.gradle.integtests.tooling.r86.ProblemsServiceModelBuilderCrossVersionTest.getBuildScriptSampleContent
import static org.gradle.integtests.tooling.r89.ProblemProgressEventCrossVersionTest.ProblemProgressListener
import static org.gradle.integtests.tooling.r89.ProblemProgressEventCrossVersionTest.failureMessage

@ToolingApiVersion(">=8.12")
@TargetGradleVersion(">=8.12")
class ProblemThresholdCrossVersionTest extends ToolingApiSpecification {

    def "The summary shows the amount of additional skipped events"() {
        given:
        def exceedingCount = 2
        buildFile getProblemReportingScript("${getProblemReportingBody(THRESHOLD + exceedingCount)}")
        def listener = new ProblemProgressListener()

        when:
        withConnection {
            it.newBuild()
                .forTasks("reportProblem")
                .addProgressListener(listener)
                .run()
        }

        then:
        def problems = listener.problems
        problems.size() == THRESHOLD
        validateProblems(THRESHOLD, problems)
        def problemSummariesEvent = listener.summariesEvent as ProblemsSummariesEvent
        problemSummariesEvent != null

        def summaries = problemSummariesEvent.problemsSummaries.problemsSummaries
        summaries.size() == 1
        summaries.get(0).count == exceedingCount
    }

    def "No summaries if no events exceeded the threshold"() {
        def totalSentEventsCount = ProblemSummarizer.THRESHOLD + exceedingCount
        given:
        buildFile getProblemReportingScript("${getProblemReportingBody(totalSentEventsCount)}")
        def listener = new ProblemProgressListener()

        when:
        withConnection {
            it.newBuild()
                .forTasks("reportProblem")
                .addProgressListener(listener)
                .run()
        }

        then:
        def problems = listener.problems
        problems.size() == totalSentEventsCount
        validateProblems(totalSentEventsCount, problems)
        def problemSummariesEvent = listener.summariesEvent as ProblemsSummariesEvent
        problemSummariesEvent != null

        def summaries = problemSummariesEvent.problemsSummaries.problemsSummaries
        summaries.size() == 0

        where:
        exceedingCount << [-5, -1, 0]
    }

    boolean validateProblems(int totalSentEventsCount, List<SingleProblemEvent> problems) {
        (0..totalSentEventsCount-1).every {
            problems[it].definition.id.displayName == 'label' &&
            problems[it].definition.id.group.displayName == 'Generic'
        }
    }

    @TargetGradleVersion(">=8.10.2 <8.11")
    def "No summaries old gradle version before 8.12"() {
        given:
        def exceedingCount = 2
        buildFile getBuildScriptSampleContent(false, false, targetVersion, THRESHOLD + exceedingCount)
        def listener = new ProblemProgressListener()

        when:
        withConnection {
            it.model(CustomModel)
                .addProgressListener(listener)
                .get()
        }

        then:
        def problems = listener.problems
        problems.size() == 1 // 1 because older version does aggregation and only sends the first one.
        validateProblems(1, problems)
        failureMessage(problems[0].failure) == 'test'
        listener.summariesEvent == null
    }

    def "Events are still sent despite one group already ran into threshold"() {
        given:
        def exceedingCount = 2
        def differentProblemCount = 4
        def threshold = THRESHOLD + exceedingCount
        buildFile getProblemReportingScript("""
            ${getProblemReportingBody(threshold)}
            ${getProblemReportingBody(differentProblemCount, "testCategory2", "label2")}
            """)
        def listener = new ProblemProgressListener()

        when:
        withConnection {
            it.newBuild()
                .addProgressListener(listener)
                .forTasks("reportProblem")
                .run()
        }

        then:
        def problems = listener.problems
        problems.size() == THRESHOLD + differentProblemCount
        validateProblems(THRESHOLD, problems)

        def problemSummariesEvent = listener.summariesEvent as ProblemsSummariesEvent

        def summaries = problemSummariesEvent.problemsSummaries.problemsSummaries
        summaries.size() == 1
    }

    String getProblemReportingBody(int threshold, String category = "testcategory", String label = "label") {
        """($threshold).times {
                 problems.getReporter().reporting {
                    it.id("$category", "$label")
                      .details('Wrong API usage, will not show up anywhere')
                 }
             }
        """
    }
}
