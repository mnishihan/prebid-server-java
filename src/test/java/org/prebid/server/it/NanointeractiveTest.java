package org.prebid.server.it;

import io.restassured.response.Response;
import org.json.JSONException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.prebid.server.model.Endpoint;
import org.springframework.test.context.junit4.SpringRunner;

import java.io.IOException;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static java.util.Collections.singletonList;

@RunWith(SpringRunner.class)
public class NanointeractiveTest extends IntegrationTest {

    @Test
    public void openrtb2AuctionShouldRespondWithBidsFromNanointeractive() throws IOException, JSONException {
        // given
        WIRE_MOCK_RULE.stubFor(post(urlPathEqualTo("/nanointeractive-exchange/"))
                .withRequestBody(equalToJson(
                        jsonFrom("openrtb2/nanointeractive/test-nanointeractive-bid-request.json")))
                .willReturn(aResponse().withBody(
                        jsonFrom("openrtb2/nanointeractive/test-nanointeractive-bid-response.json"))));

        // when
        final Response response = responseFor("openrtb2/nanointeractive/test-auction-nanointeractive-request.json",
                Endpoint.OPENRTB2_AUCTION);

        // then
        assertJsonEquals("openrtb2/nanointeractive/test-auction-nanointeractive-response.json", response,
                singletonList("nanointeractive"));
    }
}
