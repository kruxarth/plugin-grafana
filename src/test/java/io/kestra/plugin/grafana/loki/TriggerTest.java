package io.kestra.plugin.grafana.loki;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.utils.IdUtils;
import io.kestra.core.utils.TestsUtils;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@KestraTest
class TriggerTest {

    @Inject
    private RunContextFactory runContextFactory;

    private WireMockServer wireMockServer;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(0);
        wireMockServer.start();
    }

    @AfterEach
    void tearDown() {
        wireMockServer.stop();
    }

    @Test
    void shouldParseStreamsResponseWithoutClassCastException() throws Exception {
        wireMockServer.stubFor(get(urlPathMatching("/loki/api/v1/query_range.*"))
            .willReturn(okJson("""
                {
                  "status": "success",
                  "data": {
                    "resultType": "streams",
                    "result": [
                      {
                        "stream": {"job": "security", "level": "critical"},
                        "values": [
                          ["1700000000000000000", "unauthorized access attempt"]
                        ]
                      }
                    ]
                  }
                }
                """)));

        Trigger trigger = Trigger.builder()
            .id("loki-" + IdUtils.create())
            .type(Trigger.class.getName())
            .url(Property.ofValue(wireMockServer.baseUrl()))
            .query(Property.ofValue("{job=\"security\"} |= \"unauthorized access\""))
            .build();

        var context = TestsUtils.mockTrigger(runContextFactory, trigger);
        Optional<Execution> execution = trigger.evaluate(context.getKey(), context.getValue());

        assertThat(execution.isPresent(), is(true));
        assertThat(execution.get().getTrigger().getVariables().get("count"), is(1));
    }

    @Test
    void shouldNotFireOnEmptyResultSet() throws Exception {
        wireMockServer.stubFor(get(urlPathMatching("/loki/api/v1/query_range.*"))
            .willReturn(okJson("""
                {
                  "status": "success",
                  "data": {
                    "resultType": "streams",
                    "result": []
                  }
                }
                """)));

        Trigger trigger = Trigger.builder()
            .id("loki-" + IdUtils.create())
            .type(Trigger.class.getName())
            .url(Property.ofValue(wireMockServer.baseUrl()))
            .query(Property.ofValue("{job=\"security\"} |= \"unauthorized access\""))
            .build();

        var context = TestsUtils.mockTrigger(runContextFactory, trigger);
        Optional<Execution> execution = trigger.evaluate(context.getKey(), context.getValue());

        assertThat(execution.isPresent(), is(false));
    }

    @Test
    void shouldDeliverEveryLineWhenABurstExceedsMaxRecords() throws Exception {
        // Lines 99 to 102 share a timestamp, so the first page ends in the middle of them.
        stubPage(null, lines(1, 100));
        stubPage(timestamp(100), lines(99, 198));
        stubPage(timestamp(198), lines(198, 250));

        Trigger trigger = Trigger.builder()
            .id("loki-" + IdUtils.create())
            .type(Trigger.class.getName())
            .url(Property.ofValue(wireMockServer.baseUrl()))
            .query(Property.ofValue("{job=\"api\"}"))
            .build();

        List<List<String>> executions = poll(trigger, 4);

        assertThat(executions.stream().map(List::size).toList(), is(List.of(100, 100, 50)));
        assertThat(executions.stream().flatMap(List::stream).distinct().count(), is(250L));
    }

    @Test
    void shouldMovePastATimestampSharedByAFullPage() throws Exception {
        String sameTimestamp = timestamp(1);
        stubPage(null, List.of(List.of(sameTimestamp, "line 1"), List.of(sameTimestamp, "line 2")));
        stubPage(sameTimestamp, List.of(List.of(sameTimestamp, "line 1"), List.of(sameTimestamp, "line 2")));
        stubPage(String.valueOf(Long.parseLong(sameTimestamp) + 1), List.of(List.of(timestamp(5), "line 5")));

        Trigger trigger = Trigger.builder()
            .id("loki-" + IdUtils.create())
            .type(Trigger.class.getName())
            .url(Property.ofValue(wireMockServer.baseUrl()))
            .query(Property.ofValue("{job=\"api\"}"))
            .maxRecords(Property.ofValue(2))
            .build();

        assertThat(poll(trigger, 2), is(List.of(List.of("line 1", "line 2"), List.of("line 5"))));
    }

    @SuppressWarnings("unchecked")
    private List<List<String>> poll(Trigger trigger, int times) throws Exception {
        var context = TestsUtils.mockTrigger(runContextFactory, trigger);
        List<List<String>> executions = new ArrayList<>();
        for (int i = 0; i < times; i++) {
            trigger.evaluate(context.getKey(), context.getValue()).ifPresent(
                execution -> executions.add(
                    ((List<Map<String, Object>>) execution.getTrigger().getVariables().get("logs")).stream()
                        .map(log -> (String) log.get("line"))
                        .toList()
                )
            );
        }
        return executions;
    }

    private static String timestamp(int line) {
        return String.valueOf(1_700_000_000_000_000_000L + (line >= 99 && line <= 102 ? 99 : line));
    }

    private static List<List<String>> lines(int first, int last) {
        return IntStream.rangeClosed(first, last)
            .mapToObj(i -> List.of(timestamp(i), "line " + i))
            .toList();
    }

    private void stubPage(String start, List<List<String>> values) throws Exception {
        wireMockServer.stubFor(
            get(urlPathEqualTo("/loki/api/v1/query_range"))
                .withQueryParam("start", start == null ? absent() : equalTo(start))
                .willReturn(okJson("""
                    {
                      "status": "success",
                      "data": {
                        "resultType": "streams",
                        "result": [{"stream": {"job": "api"}, "values": %s}]
                      }
                    }
                    """.formatted(JacksonMapper.ofJson().writeValueAsString(values))))
        );
    }
}
