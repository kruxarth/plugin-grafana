package io.kestra.plugin.grafana.loki;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
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
        long baseTimestamp = 1_700_000_000_000_000_000L;
        stubPage(null, baseTimestamp, 1, 100);
        stubPage(String.valueOf(baseTimestamp + 100), baseTimestamp, 100, 199);
        stubPage(String.valueOf(baseTimestamp + 199), baseTimestamp, 199, 250);

        Trigger trigger = Trigger.builder()
            .id("loki-" + IdUtils.create())
            .type(Trigger.class.getName())
            .url(Property.ofValue(wireMockServer.baseUrl()))
            .query(Property.ofValue("{job=\"api\"}"))
            .build();

        var context = TestsUtils.mockTrigger(runContextFactory, trigger);
        List<String> delivered = new ArrayList<>();
        for (int poll = 0; poll < 3; poll++) {
            trigger.evaluate(context.getKey(), context.getValue()).ifPresent(execution -> {
                @SuppressWarnings("unchecked")
                var logs = (List<Map<String, Object>>) execution.getTrigger().getVariables().get("logs");
                logs.forEach(log -> delivered.add((String) log.get("line")));
            });
        }

        assertThat(delivered, hasSize(250));
        assertThat(new HashSet<>(delivered), hasSize(250));
    }

    private void stubPage(String start, long baseTimestamp, int firstLine, int lastLine) {
        String values = IntStream.rangeClosed(firstLine, lastLine)
            .mapToObj(i -> "[\"%d\", \"line %d\"]".formatted(baseTimestamp + i, i))
            .collect(Collectors.joining(","));

        wireMockServer.stubFor(get(urlPathEqualTo("/loki/api/v1/query_range"))
            .withQueryParam("start", start == null ? absent() : equalTo(start))
            .willReturn(okJson("""
                {
                  "status": "success",
                  "data": {
                    "resultType": "streams",
                    "result": [{"stream": {"job": "api"}, "values": [%s]}]
                  }
                }
                """.formatted(values))));
    }
}
