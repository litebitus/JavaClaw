package ai.javaclaw.tools;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;

class AgentEnvironmentTest {

    @Test
    void currentTimeShouldBeLocalNotUtc() {
        String info = AgentEnvironment.info().toString();

        String currentTimeLine = extractLine(info, "Current time: ");
        String rawValue = currentTimeLine.replace("Current time: ", "").trim();
        LocalDateTime parsed = LocalDateTime.parse(rawValue, DateTimeFormatter.ISO_LOCAL_DATE_TIME);

        LocalDateTime before = LocalDateTime.now().minusMinutes(1);
        LocalDateTime after = LocalDateTime.now().plusMinutes(1);

        assertThat(parsed).isAfter(before).isBefore(after);
    }

    @Test
    void currentTimeShouldNotContainUtcOffset() {
        String info = AgentEnvironment.info().toString();

        String currentTimeLine = extractLine(info, "Current time: ");

        assertThat(currentTimeLine).doesNotContain("Z").doesNotContain("+").doesNotContain("UTC");
    }

    private String extractLine(String text, String prefix) {
        for (String line : text.split(System.lineSeparator())) {
            if (line.startsWith(prefix)) {
                return line;
            }
        }
        throw new AssertionError("Line with prefix '" + prefix + "' not found in:\n" + text);
    }
}
