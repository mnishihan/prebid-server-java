package org.prebid.server.rubicon.analytics;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iab.openrtb.request.App;
import com.iab.openrtb.request.Banner;
import com.iab.openrtb.request.BidRequest;
import com.iab.openrtb.request.Device;
import com.iab.openrtb.request.Format;
import com.iab.openrtb.request.Geo;
import com.iab.openrtb.request.Imp;
import com.iab.openrtb.request.Regs;
import com.iab.openrtb.request.Site;
import com.iab.openrtb.request.User;
import com.iab.openrtb.request.Video;
import com.iab.openrtb.response.Bid;
import com.iab.openrtb.response.BidResponse;
import com.iab.openrtb.response.SeatBid;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import lombok.Value;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.prebid.server.analytics.AnalyticsReporter;
import org.prebid.server.analytics.model.AmpEvent;
import org.prebid.server.analytics.model.AuctionEvent;
import org.prebid.server.analytics.model.NotificationEvent;
import org.prebid.server.auction.IpAddressHelper;
import org.prebid.server.auction.model.AuctionContext;
import org.prebid.server.bidder.BidderCatalog;
import org.prebid.server.bidder.model.BidderError;
import org.prebid.server.cookie.UidsCookie;
import org.prebid.server.cookie.UidsCookieService;
import org.prebid.server.currency.CurrencyConversionService;
import org.prebid.server.exception.InvalidRequestException;
import org.prebid.server.exception.PreBidException;
import org.prebid.server.geolocation.CountryCodeMapper;
import org.prebid.server.geolocation.model.GeoInfo;
import org.prebid.server.json.JacksonMapper;
import org.prebid.server.log.ConditionalLogger;
import org.prebid.server.model.HttpRequestContext;
import org.prebid.server.privacy.gdpr.TcfDefinerService;
import org.prebid.server.privacy.gdpr.model.TcfContext;
import org.prebid.server.privacy.model.PrivacyContext;
import org.prebid.server.proto.openrtb.ext.ExtPrebid;
import org.prebid.server.proto.openrtb.ext.request.ExtApp;
import org.prebid.server.proto.openrtb.ext.request.ExtAppPrebid;
import org.prebid.server.proto.openrtb.ext.request.ExtImp;
import org.prebid.server.proto.openrtb.ext.request.ExtImpContext;
import org.prebid.server.proto.openrtb.ext.request.ExtImpPrebid;
import org.prebid.server.proto.openrtb.ext.request.ExtRegs;
import org.prebid.server.proto.openrtb.ext.request.ExtRequest;
import org.prebid.server.proto.openrtb.ext.request.ExtRequestCurrency;
import org.prebid.server.proto.openrtb.ext.request.ExtRequestPrebid;
import org.prebid.server.proto.openrtb.ext.request.ExtRequestPrebidChannel;
import org.prebid.server.proto.openrtb.ext.request.ExtStoredRequest;
import org.prebid.server.proto.openrtb.ext.request.ExtUser;
import org.prebid.server.proto.openrtb.ext.request.rubicon.ExtImpRubicon;
import org.prebid.server.proto.openrtb.ext.request.rubicon.RubiconVideoParams;
import org.prebid.server.proto.openrtb.ext.response.BidType;
import org.prebid.server.proto.openrtb.ext.response.ExtBidPrebid;
import org.prebid.server.proto.openrtb.ext.response.ExtBidResponse;
import org.prebid.server.proto.openrtb.ext.response.ExtBidderError;
import org.prebid.server.rubicon.analytics.proto.AdUnit;
import org.prebid.server.rubicon.analytics.proto.Auction;
import org.prebid.server.rubicon.analytics.proto.BidError;
import org.prebid.server.rubicon.analytics.proto.BidWon;
import org.prebid.server.rubicon.analytics.proto.Client;
import org.prebid.server.rubicon.analytics.proto.Dimensions;
import org.prebid.server.rubicon.analytics.proto.Error;
import org.prebid.server.rubicon.analytics.proto.Event;
import org.prebid.server.rubicon.analytics.proto.EventCreator;
import org.prebid.server.rubicon.analytics.proto.Gam;
import org.prebid.server.rubicon.analytics.proto.Gdpr;
import org.prebid.server.rubicon.analytics.proto.Impression;
import org.prebid.server.rubicon.analytics.proto.Params;
import org.prebid.server.rubicon.analytics.proto.StartDelay;
import org.prebid.server.rubicon.analytics.proto.VideoAdFormat;
import org.prebid.server.rubicon.audit.UidsAuditCookieService;
import org.prebid.server.rubicon.audit.proto.UidAudit;
import org.prebid.server.rubicon.proto.request.ExtRequestPrebidBidders;
import org.prebid.server.rubicon.proto.request.ExtRequestPrebidBiddersRubicon;
import org.prebid.server.settings.model.Account;
import org.prebid.server.settings.model.AccountAnalyticsConfig;
import org.prebid.server.settings.model.AccountAuctionEventConfig;
import org.prebid.server.util.HttpUtil;
import org.prebid.server.util.ObjectUtil;
import org.prebid.server.vertx.http.HttpClient;
import org.prebid.server.vertx.http.model.HttpClientResponse;

import java.math.BigDecimal;
import java.net.MalformedURLException;
import java.net.URL;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

public class RubiconAnalyticsReporter implements AnalyticsReporter {

    private static final Logger logger = LoggerFactory.getLogger(RubiconAnalyticsReporter.class);
    private static final ConditionalLogger conditionalLogger = new ConditionalLogger(logger);

    private static final String ADAPTER_NAME = "rubicon";
    private static final String EVENT_PATH = "/event";

    private static final String PREBID_EXT = "prebid";
    private static final String BIDDER_EXT = "bidder";
    private static final String PBADSLOT_EXT = "pbadslot";
    private static final String ADSERVER_EXT = "adserver";
    private static final String ADSLOT_EXT = "adSlot";
    private static final String NAME_EXT = "name";
    private static final String GAM_EXT = "gam";

    private static final String SUCCESS_STATUS = "success";
    private static final String NO_BID_STATUS = "no-bid";
    private static final String ERROR_STATUS = "error";

    private static final String RUBICON_BIDDER = "rubicon";

    private static final String SERVER_SOURCE = "server";

    private static final String PBS_INTEGRATION = "pbs";

    private static final String APP_DEVICE_CLASS = "APP";

    private static final String STORED_REQUEST_ID_AMP_URL_PARAM = "tag_id=";
    private static final String URL_PARAM_SEPARATOR = "&";

    private static final Map<Integer, String> VIDEO_SIZE_AD_FORMATS;

    private static final String USD_CURRENCY = "USD";

    private static final String GDPR_ONE_STRING = "1";
    private static final Integer GDPR_ONE_INTEGER = 1;

    private static final TypeReference<ExtPrebid<ExtBidPrebid, ObjectNode>> EXT_PREBID_TYPE_REFERENCE =
            new TypeReference<>() {
            };

    private static final TypeReference<ExtPrebid<ExtImpPrebid, ObjectNode>> IMP_EXT_TYPE_REFERENCE =
            new TypeReference<>() {
            };
    private static final String APPLICATION_JSON =
            HttpHeaderValues.APPLICATION_JSON + ";" + HttpHeaderValues.CHARSET + "=" + "utf-8";

    private static final String OTHER_CHANNEL = "other";
    private static final Set<String> SUPPORTED_CHANNELS = Set.of("web", "amp", "app");

    private static final String SAMPLING_FACTOR_FIELD = "sampling-factor";
    private static final String INTEGRATION_OVERRIDE_FIELD = "integration-override";

    static {
        VIDEO_SIZE_AD_FORMATS = Map.of(
                201, VideoAdFormat.PREROLL,
                202, "interstitial",
                203, "outstream",
                204, VideoAdFormat.MIDROLL,
                205, VideoAdFormat.POSTROLL,
                207, "vertical");
    }

    private final String endpointUrl;
    private final String pbsVersion;
    private final Integer globalSamplingFactor;
    private final String pbsHostname;
    private final Integer pbsHostVendorId;
    private final String dataCenterRegion;
    private final BidderCatalog bidderCatalog;
    private final UidsCookieService uidsCookieService;
    private final UidsAuditCookieService uidsAuditCookieService;
    private final CurrencyConversionService currencyService;
    private final CountryCodeMapper countryCodeMapper;
    private final IpAddressHelper ipAddressHelper;
    private final HttpClient httpClient;
    private final boolean logEmptyDimensions;
    private final JacksonMapper mapper;

    private final AtomicLong auctionEventCount = new AtomicLong();
    private final AtomicLong ampEventCount = new AtomicLong();

    private final Map<Integer, Long> accountToAuctionEventCount = new ConcurrentHashMap<>();
    private final Map<Integer, Long> accountToAmpEventCount = new ConcurrentHashMap<>();
    private final Map<Integer, Long> accountToNotificationEventCount = new ConcurrentHashMap<>();

    public RubiconAnalyticsReporter(String endpointUrl,
                                    Integer globalSamplingFactor,
                                    String pbsVersion,
                                    String pbsHostname,
                                    Integer pbsHostVendorId,
                                    String dataCenterRegion,
                                    BidderCatalog bidderCatalog,
                                    UidsCookieService uidsCookieService,
                                    UidsAuditCookieService uidsAuditCookieService,
                                    CurrencyConversionService currencyService,
                                    CountryCodeMapper countryCodeMapper,
                                    IpAddressHelper ipAddressHelper,
                                    HttpClient httpClient,
                                    boolean logEmptyDimensions,
                                    JacksonMapper mapper) {

        this.endpointUrl = HttpUtil.validateUrl(Objects.requireNonNull(endpointUrl) + EVENT_PATH);
        this.globalSamplingFactor = globalSamplingFactor;
        this.pbsVersion = pbsVersion;
        this.pbsHostname = Objects.requireNonNull(pbsHostname);
        this.pbsHostVendorId = Objects.requireNonNull(pbsHostVendorId);
        this.dataCenterRegion = Objects.requireNonNull(dataCenterRegion);
        this.bidderCatalog = Objects.requireNonNull(bidderCatalog);
        this.uidsCookieService = Objects.requireNonNull(uidsCookieService);
        this.uidsAuditCookieService = Objects.requireNonNull(uidsAuditCookieService);
        this.currencyService = Objects.requireNonNull(currencyService);
        this.countryCodeMapper = Objects.requireNonNull(countryCodeMapper);
        this.ipAddressHelper = Objects.requireNonNull(ipAddressHelper);
        this.httpClient = Objects.requireNonNull(httpClient);
        this.logEmptyDimensions = logEmptyDimensions;
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Override
    public <T> Future<Void> processEvent(T event) {
        if (event instanceof AuctionEvent) {
            return processAuctionEvent((AuctionEvent) event);
        } else if (event instanceof AmpEvent) {
            return processAmpEvent((AmpEvent) event);
        } else if (event instanceof NotificationEvent) {
            return processNotificationEvent((NotificationEvent) event);
        } else {
            return Future.succeededFuture(); // adapter doesn't need to care of all event types
        }
    }

    @Override
    public int vendorId() {
        return pbsHostVendorId;
    }

    @Override
    public String name() {
        return ADAPTER_NAME;
    }

    private Future<Void> processAuctionEvent(AuctionEvent auctionEvent) {
        final AuctionContext auctionContext = auctionEvent.getAuctionContext();
        if (auctionContext == null) { // this can happen when exception is thrown while processing
            return Future.failedFuture(new InvalidRequestException("Exception is thrown while auction processing"));
        }

        final HttpRequestContext httpContext = auctionEvent.getHttpContext();
        final BidRequest bidRequest = auctionContext.getBidRequest();
        final Account account = auctionContext.getAccount();
        final BidResponse bidResponse = auctionEvent.getBidResponse();

        if (httpContext == null || bidRequest == null || account == null || bidResponse == null) {
            return Future.failedFuture(
                    new InvalidRequestException("Necessary data is missing while auction processing"));
        }

        final String requestAccountId = account.getId();
        final Integer accountId = parseId(requestAccountId);
        final Integer accountSamplingFactor = accountSamplingFactor(account);

        if (shouldProcessEvent(
                account, accountId, bidRequest, accountSamplingFactor, accountToAuctionEventCount, auctionEventCount)) {

            final UidsCookie uidsCookie = uidsCookieService.parseFromCookies(HttpUtil.cookiesAsMap(httpContext));

            final Event event = toAuctionEvent(
                    httpContext,
                    auctionContext,
                    toAdUnits(bidRequest, uidsCookie, bidResponse, requestAccountId),
                    accountId,
                    accountSamplingFactor,
                    this::eventBuilderBase);

            return postEvent(event, headers(event, bidRequest), isDebugEnabled(bidRequest));
        }
        return Future.succeededFuture();
    }

    private Future<Void> processAmpEvent(AmpEvent ampEvent) {
        final AuctionContext auctionContext = ampEvent.getAuctionContext();
        if (auctionContext == null) { // this can happen when exception is thrown while processing
            return Future.failedFuture(new InvalidRequestException("Exception is thrown while amp processing"));
        }

        final HttpRequestContext httpContext = ampEvent.getHttpContext();
        final BidRequest bidRequest = auctionContext.getBidRequest();
        final Account account = auctionContext.getAccount();
        final BidResponse bidResponse = ampEvent.getBidResponse();

        if (httpContext == null || bidRequest == null || account == null || bidResponse == null) {
            return Future.failedFuture(new InvalidRequestException("Necessary data is missing while amp processing"));
        }

        final String requestAccountId = account.getId();
        final Integer accountId = NumberUtils.isDigits(requestAccountId) ? parseId(requestAccountId) : null;
        final Integer accountSamplingFactor = accountSamplingFactor(account);
        final String storedId = parseStoredId(httpContext.getAbsoluteUri());

        // only continue if counter matches sampling factor
        if (shouldProcessEvent(
                account, accountId, bidRequest, accountSamplingFactor, accountToAmpEventCount, ampEventCount)) {

            final UidsCookie uidsCookie = uidsCookieService.parseFromCookies(HttpUtil.cookiesAsMap(httpContext));

            final Event event = toAuctionEvent(
                    httpContext,
                    auctionContext,
                    toAdUnits(bidRequest, uidsCookie, bidResponse, storedId, requestAccountId),
                    accountId,
                    accountSamplingFactor,
                    this::eventBuilderBase);

            return postEvent(event, headers(event, bidRequest), isDebugEnabled(bidRequest));
        }
        return Future.succeededFuture();
    }

    private Future<Void> processNotificationEvent(NotificationEvent notificationEvent) {
        final String bidId = notificationEvent.getBidId();
        final Account account = notificationEvent.getAccount();
        final HttpRequestContext httpContext = notificationEvent.getHttpContext();
        if (bidId == null || account == null || httpContext == null) {
            return Future.failedFuture(new InvalidRequestException("Necessary data is missing while event processing"));
        }

        final Integer accountId = parseId(account.getId());
        final Integer accountSamplingFactor = accountSamplingFactor(account);

        // only continue if counter matches sampling factor
        // note: this event type doesn't use global event count because it is related to particular account
        final Integer samplingFactor = samplingFactor(accountSamplingFactor);
        if (samplingFactor != null && samplingFactor > 0) {
            final long eventCount = accountToNotificationEventCount.compute(accountId,
                    (ignored, oldValue) -> oldValue == null ? 1L : oldValue + 1);
            if (eventCount % samplingFactor != 0) {
                return Future.succeededFuture();
            }
        } else {
            return Future.succeededFuture();
        }

        final String bidder = notificationEvent.getBidder();
        final UidsCookie uidsCookie = uidsCookieService.parseFromCookies(HttpUtil.cookiesAsMap(httpContext));
        final Long timestamp = notificationEvent.getTimestamp();
        final String integration = notificationEvent.getIntegration();

        final NotificationEvent.Type type = notificationEvent.getType();
        final Event event = type == NotificationEvent.Type.win
                ? makeWinEvent(bidId, bidder, account, accountId, httpContext, uidsCookie, timestamp, integration)
                : makeImpEvent(bidId, bidder, account, accountId, httpContext, uidsCookie, timestamp, integration);

        return postEvent(event, headers(event), false);
    }

    private boolean shouldProcessEvent(Account account,
                                       Integer accountId,
                                       BidRequest bidRequest,
                                       Integer accountSamplingFactor,
                                       Map<Integer, Long> accountToEventCount,
                                       AtomicLong globalEventCount) {

        if (!isChannelSupported(bidRequest, account) || isClientAnalytics(bidRequest)) {
            return false;
        }

        if (accountSamplingFactor != null && accountSamplingFactor > 0) {
            final long eventCount = accountToEventCount.compute(accountId,
                    (ignored, oldValue) -> oldValue == null ? 1L : oldValue + 1);
            return eventCount % accountSamplingFactor == 0;
        } else if (globalSamplingFactor != null && globalSamplingFactor > 0) {
            return globalEventCount.incrementAndGet() % globalSamplingFactor == 0;
        } else {
            return false;
        }
    }

    private static boolean isChannelSupported(BidRequest bidRequest, Account account) {
        final AccountAnalyticsConfig analyticsConfig = account.getAnalytics();
        final AccountAuctionEventConfig accountAuctionEventConfig =
                ObjectUtil.getIfNotNull(analyticsConfig, AccountAnalyticsConfig::getAuctionEvents);
        final Map<String, Boolean> accountAuctionEvents =
                ObjectUtil.getIfNotNull(accountAuctionEventConfig, AccountAuctionEventConfig::getEvents);
        final Map<String, Boolean> channelConfig =
                ObjectUtils.defaultIfNull(accountAuctionEvents, AccountAnalyticsConfig.fallbackAuctionEvents());

        final String channelFromRequest = channelFromRequest(bidRequest);

        return MapUtils.emptyIfNull(channelConfig).entrySet().stream()
                .filter(entry -> StringUtils.equalsIgnoreCase(channelFromRequest, entry.getKey()))
                .findFirst()
                .map(entry -> BooleanUtils.isTrue(entry.getValue()))
                .orElse(Boolean.FALSE);
    }

    private boolean isClientAnalytics(BidRequest bidRequest) {
        final ExtRequest extRequest = bidRequest.getExt();
        final ExtRequestPrebid extPrebid = extRequest != null ? extRequest.getPrebid() : null;
        final JsonNode analytics = extPrebid != null ? extPrebid.getAnalytics() : null;
        final JsonNode rubiconAnalyticsNode = isNotEmptyObjectNode(analytics) ? analytics.get(name()) : null;
        final JsonNode clientAnalyticNode = isNotEmptyObjectNode(rubiconAnalyticsNode)
                ? rubiconAnalyticsNode.get("client-analytics") : null;

        return clientAnalyticNode != null
                && clientAnalyticNode.isBoolean()
                && clientAnalyticNode.booleanValue();
    }

    private static boolean isNotEmptyObjectNode(JsonNode analytics) {
        return analytics != null && analytics.isObject() && !analytics.isEmpty();
    }

    private static String channelFromRequest(BidRequest bidRequest) {
        return recogniseChannelName(
                getIfNotNull(getIfNotNull(getIfNotNull(bidRequest.getExt(),
                                        ExtRequest::getPrebid),
                                ExtRequestPrebid::getChannel),
                        ExtRequestPrebidChannel::getName));
    }

    // TODO: remove alias resolving after transition period
    private static String recogniseChannelName(String channelName) {
        if (StringUtils.equalsIgnoreCase("pbjs", channelName)) {
            return "web";
        }

        return channelName;
    }

    private static String parseStoredId(String uri) {
        // substringBetween can't handle "amp?tag_id=1001"
        final String tagIdValueAndOthers = StringUtils.substringAfter(uri, STORED_REQUEST_ID_AMP_URL_PARAM);
        return StringUtils.substringBefore(tagIdValueAndOthers, URL_PARAM_SEPARATOR);
    }

    private Event makeWinEvent(
            String bidId,
            String bidder,
            Account account,
            Integer accountId,
            HttpRequestContext httpContext,
            UidsCookie uidsCookie,
            Long timestamp,
            String integration) {

        return eventBuilderFromNotification(httpContext, timestamp, integration, account)
                .bidsWon(Collections.singletonList(BidWon.builder()
                        .bidId(bidId)
                        .bidder(bidder)
                        .accountId(accountId)
                        .status(SUCCESS_STATUS)
                        .source(SERVER_SOURCE)
                        .serverHasUserId(serverHasUserIdFrom(uidsCookie, RUBICON_BIDDER))
                        .hasRubiconId(hasRubiconId(httpContext))
                        .build()))
                .build();
    }

    private Event makeImpEvent(
            String bidId,
            String bidder,
            Account account,
            Integer accountId,
            HttpRequestContext httpContext,
            UidsCookie uidsCookie,
            Long timestamp,
            String integration) {

        return eventBuilderFromNotification(httpContext, timestamp, integration, account)
                .impressions(Collections.singletonList(Impression.builder()
                        .bidId(bidId)
                        .bidder(bidder)
                        .accountId(accountId)
                        .status(SUCCESS_STATUS)
                        .source(SERVER_SOURCE)
                        .serverHasUserId(serverHasUserIdFrom(uidsCookie, RUBICON_BIDDER))
                        .hasRubiconId(hasRubiconId(httpContext))
                        .build()))
                .build();
    }

    private Event.EventBuilder eventBuilderFromNotification(
            HttpRequestContext httpContext,
            Long timestamp,
            String integration,
            Account account) {

        final String referrerUri = httpContext.getHeaders().get(HttpUtil.REFERER_HEADER.toString());

        return Event.builder()
                .eventTimeMillis(timestamp != null ? timestamp : Instant.now().toEpochMilli())
                .integration(
                        ObjectUtils.firstNonNull(accountIntegrationOverride(account), integration, PBS_INTEGRATION))
                .version(pbsVersion)
                .referrerUri(referrerUri)
                .referrerHostname(referrerHostname(referrerUri))
                .limitAdTracking(StringUtils.equals(httpContext.getHeaders().get(HttpUtil.DNT_HEADER.toString()), "1"))
                .userAgent(httpContext.getHeaders().get(HttpUtil.USER_AGENT_HEADER.toString()))
                .eventCreator(EventCreator.of(pbsHostname, dataCenterRegion));
    }

    private static String referrerHostname(String referrerUri) {
        if (StringUtils.isBlank(referrerUri)) {
            return null;
        }

        try {
            return new URL(referrerUri).getHost();
        } catch (MalformedURLException e) {
            logger.warn("Could not extract hostname from referrer uri [{0}], error: {1}", referrerUri, e.getMessage());
            return null;
        }
    }

    private static boolean isDebugEnabled(BidRequest bidRequest) {
        final ExtRequest ext = bidRequest.getExt();
        final ExtRequestPrebid prebid = ext != null ? ext.getPrebid() : null;
        final Integer debug = prebid != null ? prebid.getDebug() : null;
        return Objects.equals(debug, 1);
    }

    // AMP
    private List<AdUnit> toAdUnits(BidRequest bidRequest,
                                   UidsCookie uidsCookie,
                                   BidResponse bidResponse,
                                   String storedId,
                                   String accountId) {

        final Map<String, List<TwinBids>> impIdToBids = toBidsByImpId(bidRequest, uidsCookie, bidResponse, accountId);

        return bidRequest.getImp().stream()
                .map(imp -> toAdUnit(bidRequest, imp, impIdToBids.getOrDefault(imp.getId(), Collections.emptyList()),
                        storedId, accountId))
                .collect(Collectors.toList());
    }

    // Auction
    private List<AdUnit> toAdUnits(BidRequest bidRequest,
                                   UidsCookie uidsCookie,
                                   BidResponse bidResponse,
                                   String accountId) {

        final Map<String, List<TwinBids>> impIdToBids = toBidsByImpId(bidRequest, uidsCookie, bidResponse, accountId);

        return bidRequest.getImp().stream()
                .map(imp -> toAdUnit(bidRequest, imp, impIdToBids.getOrDefault(imp.getId(), Collections.emptyList()),
                        accountId))
                .collect(Collectors.toList());
    }

    private Map<String, List<TwinBids>> toBidsByImpId(BidRequest bidRequest,
                                                      UidsCookie uidsCookie,
                                                      BidResponse bidResponse,
                                                      String accountId) {
        final ExtBidResponse extBidResponse = bidResponse.getExt();
        final Map<String, List<TwinBids>> impIdToBids = new HashMap<>();

        populateSuccessfulBids(bidRequest, uidsCookie, bidResponse, extBidResponse, impIdToBids, accountId);
        populateFailedBids(bidRequest, uidsCookie, extBidResponse, impIdToBids, accountId);

        return impIdToBids;
    }

    private void populateSuccessfulBids(BidRequest bidRequest,
                                        UidsCookie uidsCookie,
                                        BidResponse bidResponse,
                                        ExtBidResponse extBidResponse,
                                        Map<String, List<TwinBids>> impIdToBids,
                                        String accountId) {

        final String currency = bidRequest.getCur().get(0);
        final Map<String, Map<String, BigDecimal>> requestCurrencyRates = requestCurrencyRates(bidRequest.getExt());
        final Boolean usepbsrates = usepbsrates(bidRequest.getExt());

        for (final SeatBid seatBid : bidResponse.getSeatbid()) {
            final String bidder = seatBid.getSeat();
            final Integer responseTime = serverLatencyMillisFrom(extBidResponse, bidder);
            final Boolean serverHasUserId = serverHasUserIdFrom(uidsCookie, bidder);

            for (final Bid bid : seatBid.getBid()) {
                final String impId = bid.getImpid();
                final Imp imp = findImpById(bidRequest.getImp(), impId);

                impIdToBids.computeIfAbsent(impId, key -> new ArrayList<>())
                        .add(toTwinBids(bidder, imp, bid, SUCCESS_STATUS, null, responseTime, serverHasUserId,
                                currency, requestCurrencyRates, usepbsrates, accountId));
            }
        }
    }

    private void populateFailedBids(BidRequest bidRequest,
                                    UidsCookie uidsCookie,
                                    ExtBidResponse extBidResponse,
                                    Map<String, List<TwinBids>> impIdToBids,
                                    String accountId) {

        for (Imp imp : bidRequest.getImp()) {
            final ObjectNode impExt = imp.getExt();
            if (impExt == null) {
                continue;
            }

            final String impId = imp.getId();

            final JsonNode bidderParamsNode = impExt.get(PREBID_EXT).get(BIDDER_EXT);
            final Iterator<String> bidderIterator =
                    bidderParamsNode != null ? bidderParamsNode.fieldNames() : Collections.emptyIterator();
            while (bidderIterator.hasNext()) {
                final String bidder = bidderIterator.next();
                if (analyticsBidExists(impIdToBids, impId, bidder)) {
                    continue;
                }

                final BidError bidError = bidErrorFrom(extBidResponse, bidder, impId);
                final String status = bidError != null ? ERROR_STATUS : NO_BID_STATUS;

                final Integer responseTime = serverLatencyMillisFrom(extBidResponse, bidder);
                final Boolean serverHasUserId = serverHasUserIdFrom(uidsCookie, bidder);

                impIdToBids.computeIfAbsent(impId, key -> new ArrayList<>())
                        .add(toTwinBids(bidder, imp, null, status, bidError, responseTime, serverHasUserId, null,
                                null, null, accountId));
            }
        }
    }

    /**
     * Extracts usepbsrates flag from {@link ExtRequest}.
     */
    private static Boolean usepbsrates(ExtRequest requestExt) {
        final ExtRequestPrebid prebid = requestExt != null ? requestExt.getPrebid() : null;
        final ExtRequestCurrency currency = prebid != null ? prebid.getCurrency() : null;
        return currency != null ? currency.getUsepbsrates() : null;
    }

    /**
     * Determines {@link BidError} if possible or returns null.
     */
    private static BidError bidErrorFrom(ExtBidResponse extBidResponse, String bidder, String impId) {
        final Map<String, List<ExtBidderError>> bidderToErrors = extBidResponse.getErrors();
        final List<ExtBidderError> bidderErrors = bidderToErrors != null ? bidderToErrors.get(bidder) : null;
        if (bidderErrors != null) {
            for (ExtBidderError extBidderError : bidderErrors) {
                final Set<String> impIds = extBidderError.getImpIds();
                if (CollectionUtils.isNotEmpty(impIds) && impIds.contains(impId)) {
                    return bidErrorFrom(extBidderError);
                }
            }
        }
        return null;
    }

    private static BidError bidErrorFrom(ExtBidderError extBidderError) {
        final BidError result;

        final BidderError.Type errorType = BidderError.Type.getByCode(extBidderError.getCode());
        if (errorType != null) {
            if (errorType == BidderError.Type.timeout) {
                result = BidError.timeoutError(extBidderError.getMessage());
            } else {
                result = BidError.requestError(extBidderError.getMessage());
            }
        } else {
            result = null;
        }

        return result;
    }

    private static boolean analyticsBidExists(Map<String, List<TwinBids>> impIdToBids, String impId, String bidder) {
        return impIdToBids.containsKey(impId) && impIdToBids.get(impId).stream()
                .anyMatch(twinBids -> Objects.equals(bidder, twinBids.getAnalyticsBid().getBidder()));
    }

    private static Integer serverLatencyMillisFrom(ExtBidResponse extBidResponse, String bidder) {
        return extBidResponse != null
                ? extBidResponse.getResponsetimemillis().get(bidder)
                : null;
    }

    private Boolean serverHasUserIdFrom(UidsCookie uidsCookie, String bidder) {
        return bidderCatalog.isValidName(bidder)
                ? uidsCookie.hasLiveUidFrom(bidderCatalog.usersyncerByName(bidder).getCookieFamilyName())
                : null;
    }

    private boolean hasRubiconId(HttpRequestContext httpContext) {
        return uidsCookieService.parseHostCookie(HttpUtil.cookiesAsMap(httpContext)) != null;
    }

    private static Imp findImpById(List<Imp> imps, String impId) {
        return imps.stream()
                .filter(imp -> Objects.equals(imp.getId(), impId))
                .findFirst()
                .orElse(null);
    }

    private static Map<String, Map<String, BigDecimal>> requestCurrencyRates(ExtRequest extRequest) {
        final ExtRequestPrebid prebid = extRequest != null
                ? extRequest.getPrebid()
                : null;

        final ExtRequestCurrency currency = prebid != null ? prebid.getCurrency() : null;
        return currency != null ? currency.getRates() : null;
    }

    private TwinBids toTwinBids(String bidder,
                                Imp imp,
                                Bid bid,
                                String status,
                                BidError bidError,
                                Integer serverLatencyMillis,
                                Boolean serverHasUserId,
                                String currency,
                                Map<String, Map<String, BigDecimal>> requestCurrencyRates,
                                Boolean usepbsrates,
                                String accountId) {
        final ExtPrebid<ExtBidPrebid, ObjectNode> extPrebid = bid != null ? readExtPrebid(bid.getExt()) : null;
        final ExtBidPrebid extBidPrebid = extPrebid != null ? extPrebid.getPrebid() : null;
        final String targetBidderCode = extBidPrebid != null ? extBidPrebid.getTargetBidderCode() : null;
        final org.prebid.server.rubicon.analytics.proto.Bid analyticsBid =
                org.prebid.server.rubicon.analytics.proto.Bid.builder()
                        .bidId(bidIdFromBid(getIfNotNull(bid, Bid::getId), extPrebid))
                        .bidder(bidder)
                        .bidderDetail(StringUtils.isNotBlank(targetBidderCode) ? targetBidderCode : null)
                        .status(status)
                        .error(bidError)
                        .source(SERVER_SOURCE)
                        .serverLatencyMillis(serverLatencyMillis)
                        .serverHasUserId(serverHasUserId)
                        .params(paramsFrom(imp, bidder))
                        .bidResponse(analyticsBidResponse(bid, mediaTypeString(mediaTypeFromBid(extPrebid)), currency,
                                requestCurrencyRates, usepbsrates, bidder, accountId))
                        .build();

        return new TwinBids(bid, analyticsBid);
    }

    private String bidIdFromBid(String bidId, ExtPrebid<ExtBidPrebid, ObjectNode> extPrebid) {
        return ObjectUtils.firstNonNull(
                getIfNotNull(getIfNotNull(extPrebid, ExtPrebid::getPrebid), ExtBidPrebid::getBidid),
                bidId);
    }

    private Params paramsFrom(Imp imp, String bidder) {
        if (imp != null && Objects.equals(bidder, RUBICON_BIDDER)) {
            final ExtImpRubicon parsedImpExt = getFromImpExt(imp);

            return parsedImpExt != null
                    ? Params.of(parsedImpExt.getAccountId(), parsedImpExt.getSiteId(), parsedImpExt.getZoneId())
                    : null;
        }
        return null;
    }

    private BidType mediaTypeFromBid(ExtPrebid<ExtBidPrebid, ObjectNode> extBid) {
        final ExtBidPrebid extBidPrebid = extBid != null ? extBid.getPrebid() : null;
        return extBidPrebid != null ? extBidPrebid.getType() : null;
    }

    private static String mediaTypeString(BidType bidType) {
        return bidType != null ? bidType.name() : null;
    }

    private ExtImpRubicon getFromImpExt(Imp imp) {
        final ObjectNode impExt = imp.getExt();
        final JsonNode rubiconParamsNode = impExt != null
                ? imp.getExt().path(PREBID_EXT).path(BIDDER_EXT).path(RUBICON_BIDDER)
                : null;

        return rubiconParamsNode != null && !rubiconParamsNode.isMissingNode()
                ? readExt((ObjectNode) rubiconParamsNode, ExtImpRubicon.class)
                : null;
    }

    private <T> T readExt(ObjectNode ext, Class<T> type) {
        if (ext == null) {
            return null;
        }

        try {
            return mapper.mapper().treeToValue(ext, type);
        } catch (JsonProcessingException e) {
            logger.warn("Error unmarshalling ext by class {0}", e, type);
            return null;
        }
    }

    private ExtPrebid<ExtBidPrebid, ObjectNode> readExtPrebid(ObjectNode ext) {
        if (ext == null) {
            return null;
        }

        try {
            return mapper.mapper().convertValue(ext, EXT_PREBID_TYPE_REFERENCE);
        } catch (IllegalArgumentException e) {
            logger.warn("Error unmarshalling ext by type reference {0}", e, EXT_PREBID_TYPE_REFERENCE);
            return null;
        }
    }

    private org.prebid.server.rubicon.analytics.proto.BidResponse analyticsBidResponse(
            Bid bid,
            String mediaType,
            String currency,
            Map<String, Map<String, BigDecimal>> requestCurrencyRates,
            Boolean usepbsrates,
            String bidder,
            String accountId) {

        return bid != null
                ? org.prebid.server.rubicon.analytics.proto.BidResponse.of(
                parseId(bid.getDealid()),
                convertToUSD(bid.getPrice(), currency, requestCurrencyRates, usepbsrates),
                mediaType,
                validDimensions("bid", bid.getW(), bid.getH(), bidder, accountId))
                : null;
    }

    private BigDecimal convertToUSD(
            BigDecimal price, String currency, Map<String, Map<String, BigDecimal>> requestCurrencyRates,
            Boolean usepbsrates) {

        try {
            return currencyService.convertCurrency(price, requestCurrencyRates, USD_CURRENCY, currency, usepbsrates);
        } catch (PreBidException e) {
            logger.info("Unable to covert bid currency {0} to desired ad server currency {1}. {2}",
                    currency, USD_CURRENCY, e.getMessage());
            return null;
        }
    }

    // Auction
    private AdUnit toAdUnit(BidRequest bidRequest, Imp imp, List<TwinBids> bids, String accountId) {
        final ExtPrebid<ExtImpPrebid, ExtImpRubicon> extPrebid = extPrebidFromImp(imp);
        final Params params = paramsFromPrebid(extPrebid.getBidder());
        final String storedImpId = storedRequestId(extPrebid.getPrebid());

        return toAdUnit(bidRequest, imp, bids, params, storedImpId, accountId);

    }

    // AMP
    private AdUnit toAdUnit(BidRequest bidRequest, Imp imp, List<TwinBids> bids, String storedImpId, String accountId) {
        final ExtPrebid<ExtImpPrebid, ExtImpRubicon> extPrebid = extPrebidFromImp(imp);
        final Params params = paramsFromPrebid(extPrebid.getBidder());

        return toAdUnit(bidRequest, imp, bids, params, storedImpId, accountId);
    }

    private AdUnit toAdUnit(BidRequest bidRequest,
                            Imp imp,
                            List<TwinBids> bids,
                            Params params,
                            String storedId,
                            String accountId) {

        final boolean openrtbBidsFound = bids.stream().map(TwinBids::getOpenrtbBid).anyMatch(Objects::nonNull);

        final boolean errorsFound = bids.stream()
                .map(TwinBids::getAnalyticsBid)
                .map(org.prebid.server.rubicon.analytics.proto.Bid::getError)
                .anyMatch(Objects::nonNull);

        final ExtImp extImp = extImpFrom(imp);

        final List<org.prebid.server.rubicon.analytics.proto.Bid> analyticBids = bids.stream()
                .map(TwinBids::getAnalyticsBid)
                .collect(Collectors.toList());

        final String bidder = analyticBids.stream()
                .map(org.prebid.server.rubicon.analytics.proto.Bid::getBidder)
                .filter(Objects::nonNull)
                .findAny()
                .orElse(null);

        return AdUnit.builder()
                .transactionId(transactionIdFrom(bidRequest.getId(), imp.getId()))
                .status(openrtbBidsFound ? SUCCESS_STATUS : errorsFound ? ERROR_STATUS : NO_BID_STATUS)
                .error(openrtbBidsFound ? null : pickAdUnitError(bids))
                .mediaTypes(mediaTypesFromImp(imp))
                .videoAdFormat(imp.getVideo() != null ? videoAdFormatFromImp(imp, bids) : null)
                .dimensions(dimensions(imp, bidder, accountId))
                .adUnitCode(storedId)
                .pbAdSlot(pbAdSlotFromExtImp(extImp))
                .gam(gamFromExtImp(extImp))
                .siteId(params.getSiteId())
                .zoneId(params.getZoneId())
                .adserverTargeting(targetingForImp(bids))
                .bids(analyticBids)
                .build();
    }

    private ExtPrebid<ExtImpPrebid, ExtImpRubicon> extPrebidFromImp(Imp imp) {
        final ObjectNode impExt = imp != null ? imp.getExt() : null;
        if (impExt == null) {
            return ExtPrebid.of(null, null);
        }
        try {
            final ExtImpPrebid prebid =
                    mapper.mapper().convertValue(impExt, IMP_EXT_TYPE_REFERENCE).getPrebid();

            final JsonNode impExtPrebidBidder = impExt.get(PREBID_EXT).get(BIDDER_EXT);
            final ObjectNode impExtRubicon =
                    impExtPrebidBidder != null ? (ObjectNode) impExtPrebidBidder.get(RUBICON_BIDDER) : null;
            final ExtImpRubicon impRubicon = impExtRubicon == null ? null : readExt(impExtRubicon, ExtImpRubicon.class);
            return ExtPrebid.of(prebid, impRubicon);
        } catch (IllegalArgumentException e) {
            logger.warn("Error unmarshalling ext by type reference {0}", e, IMP_EXT_TYPE_REFERENCE);
            return ExtPrebid.of(null, null);
        }
    }

    private static Params paramsFromPrebid(ExtImpRubicon impExt) {
        return impExt != null
                ? Params.of(impExt.getAccountId(), impExt.getSiteId(), impExt.getZoneId())
                : Params.empty();
    }

    private static String storedRequestId(ExtImpPrebid impPrebid) {
        final ExtStoredRequest storedRequest = impPrebid != null ? impPrebid.getStoredrequest() : null;
        return storedRequest != null ? storedRequest.getId() : null;
    }

    private static String transactionIdFrom(String bidRequestId, String impId) {
        return String.format("%s-%s", bidRequestId, impId);
    }

    private static Error pickAdUnitError(List<TwinBids> bids) {
        final List<BidError> bidErrors = bids.stream()
                .map(TwinBids::getAnalyticsBid)
                .map(org.prebid.server.rubicon.analytics.proto.Bid::getError)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        if (allErrorsAreTimeouts(bidErrors)) {
            final BidError firstError = bidErrors.get(0);
            return Error.of(firstError.getCode(), firstError.getDescription());
        }

        // multiple errors with different type exist, we do not have insight what to choose
        return null;
    }

    private static boolean allErrorsAreTimeouts(List<BidError> bidErrors) {
        return bidErrors.stream().map(BidError::getCode).distinct().count() == 1
                && Objects.equals(bidErrors.get(0).getCode(), BidError.TIMEOUT_ERROR);
    }

    private static List<String> mediaTypesFromImp(Imp imp) {
        final List<String> mediaTypes = new ArrayList<>();
        if (imp.getBanner() != null) {
            mediaTypes.add(BidType.banner.name());
        }
        if (imp.getVideo() != null) {
            mediaTypes.add(BidType.video.name());
        }
        return mediaTypes;
    }

    private String videoAdFormatFromImp(Imp imp, List<TwinBids> bids) {
        final boolean hasRubiconBid = bids.stream()
                .map(TwinBids::getAnalyticsBid)
                .anyMatch(RubiconAnalyticsReporter::isRubiconVideoBid);

        return videoAdFormat(imp, hasRubiconBid);
    }

    private static boolean isRubiconVideoBid(org.prebid.server.rubicon.analytics.proto.Bid bid) {
        return Objects.equals(bid.getBidder(), RUBICON_BIDDER)
                && bid.getBidResponse() != null
                && Objects.equals(bid.getBidResponse().getMediaType(), BidType.video.name());
    }

    private String videoAdFormat(Imp imp, boolean hasRubiconBid) {
        if (hasRubiconBid) {
            final ExtImpRubicon parsedImpExt = getFromImpExt(imp);
            final RubiconVideoParams videoParams = ObjectUtil.getIfNotNull(parsedImpExt, ExtImpRubicon::getVideo);

            if (videoParams != null) {
                return VIDEO_SIZE_AD_FORMATS.get(videoParams.getSizeId());
            }
        }

        final Integer startDelay = imp.getVideo().getStartdelay();
        if (startDelay != null) {
            if (startDelay == StartDelay.GENERIC_MIDROLL || startDelay > StartDelay.PREROLL) {
                return VideoAdFormat.MIDROLL;
            } else if (startDelay == StartDelay.GENERIC_POSTROLL) {
                return VideoAdFormat.POSTROLL;
            } else {
                return VideoAdFormat.PREROLL;
            }
        }

        return null;
    }

    private List<Dimensions> dimensions(Imp imp, String bidder, String accountId) {
        final Banner banner = imp.getBanner();
        final List<Format> bannerFormat = banner != null ? banner.getFormat() : null;
        final Video video = imp.getVideo();
        List<Dimensions> resultDimensions = null;

        if (bannerFormat != null) {
            resultDimensions = bannerFormat.stream()
                    .map(f -> validDimensions("imp.banner.format", f.getW(), f.getH(), bidder, accountId))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        } else if (video != null) {
            final Dimensions validDimensions = validDimensions("imp.video", video.getW(), video.getH(), bidder,
                    accountId);
            resultDimensions = validDimensions != null
                    ? Collections.singletonList(validDimensions)
                    : Collections.emptyList();
        }

        return resultDimensions;
    }

    private Dimensions validDimensions(String placement, Integer w, Integer h, String bidder, String accountId) {
        if (w == null || h == null) {
            logInvalidDimension(placement, w, bidder, accountId);
            return null;
        }

        return Dimensions.of(w, h);
    }

    private void logInvalidDimension(String placement, Integer w, String bidder, String accountId) {
        if (logEmptyDimensions) {
            final String invalidParameter = w == null ? "h" : "w";
            final String errorPlacement = String.format("%s%s", placement, invalidParameter);
            final String message = String.format("Bid from bidder %s and with account id %s missing value %s. "
                    + "This value is required for `dimensions` sizes.", bidder, accountId, errorPlacement);

            conditionalLogger.errorWithKey(bidder, message, 100);
        }
    }

    private ExtImp extImpFrom(Imp imp) {
        try {
            return mapper.mapper().treeToValue(imp.getExt(), ExtImp.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static String pbAdSlotFromExtImp(ExtImp extImp) {
        return getIfNotNull(
                getIfNotNull(
                        getIfNotNull(
                                getIfNotNull(extImp, ExtImp::getContext), ExtImpContext::getData),
                        data -> data.get(PBADSLOT_EXT)),
                pbadslotNode -> pbadslotNode.isTextual() ? pbadslotNode.textValue() : null);
    }

    private static Gam gamFromExtImp(ExtImp extImp) {
        final ObjectNode adserverNode = getIfNotNull(getIfNotNull(getIfNotNull(getIfNotNull(extImp,
                                ExtImp::getContext),
                        ExtImpContext::getData), data -> data.isObject() ? data.get(ADSERVER_EXT) : null),
                adserver -> adserver.isObject() ? (ObjectNode) adserver : null);

        final String adserverName =
                getIfNotNull(getIfNotNull(adserverNode, adserver -> adserver.get(NAME_EXT)),
                        name -> name.isTextual()
                                ? name.textValue()
                                : null);

        if (!StringUtils.equals(adserverName, GAM_EXT)) {
            return null;
        }

        final String adSlot = getIfNotNull(getIfNotNull(adserverNode, adserver -> adserver.get(ADSLOT_EXT)),
                adSlotNode -> adSlotNode.isTextual() ? adSlotNode.textValue() : null);

        return StringUtils.isNotBlank(adSlot) ? Gam.of(adSlot) : null;
    }

    private Map<String, String> targetingForImp(List<TwinBids> bids) {
        return bids.stream()
                .map(this::targetingFromBid)
                .filter(Objects::nonNull)
                .reduce((first, second) -> second)
                .orElse(null);
    }

    private Map<String, String> targetingFromBid(TwinBids bid) {
        final Bid openrtbBid = bid.getOpenrtbBid();
        final ExtPrebid<ExtBidPrebid, ObjectNode> extBid = openrtbBid != null
                ? readExtPrebid(openrtbBid.getExt())
                : null;
        return extBid != null ? extBid.getPrebid().getTargeting() : null;
    }

    private Event toAuctionEvent(HttpRequestContext httpContext,
                                 AuctionContext auctionContext,
                                 List<AdUnit> adUnits,
                                 Integer accountId,
                                 Integer accountSamplingFactor,
                                 BiFunction<HttpRequestContext, AuctionContext, Event.EventBuilder> eventBuilderBase) {

        final BidRequest bidRequest = auctionContext.getBidRequest();

        return eventBuilderBase.apply(httpContext, auctionContext)
                .auctions(Collections.singletonList(Auction.of(
                        bidRequest.getId(),
                        samplingFactor(accountSamplingFactor),
                        adUnits,
                        accountId,
                        bidRequest.getTmax(),
                        hasRubiconId(httpContext),
                        gdpr(auctionContext))))
                .build();
    }

    private static Integer parseId(String id) {
        try {
            return NumberUtils.createInteger(id);
        } catch (NumberFormatException e) {
            logger.warn("Id [{0}] is not a number", id);
            return null;
        }
    }

    private Integer samplingFactor(Integer accountSamplingFactor) {
        return accountSamplingFactor != null && accountSamplingFactor > 0
                ? accountSamplingFactor
                : globalSamplingFactor;
    }

    private static Gdpr gdpr(AuctionContext auctionContext) {
        final PrivacyContext privacyContext = auctionContext.getPrivacyContext();
        final TcfContext tcfContext = privacyContext.getTcfContext();
        final BidRequest bidRequest = auctionContext.getBidRequest();

        final boolean pbsApplies = tcfContext.isInGdprScope();

        final Integer gdpr = getIfNotNull(getIfNotNull(getIfNotNull(bidRequest,
                                BidRequest::getRegs),
                        Regs::getExt),
                ExtRegs::getGdpr);
        final Boolean applies = Objects.equals(gdpr, GDPR_ONE_INTEGER);

        final String consentString = getIfNotNull(getIfNotNull(getIfNotNull(bidRequest,
                                BidRequest::getUser),
                        User::getExt),
                ExtUser::getConsent);

        final Integer version = TcfDefinerService.isConsentValid(tcfContext.getConsent())
                ? tcfContext.getConsent().getVersion()
                : null;

        return Gdpr.of(pbsApplies, applies, consentString, version);
    }

    private static Client clientFrom(BidRequest bidRequest) {
        final org.prebid.server.rubicon.analytics.proto.App app = clientAppFrom(bidRequest.getApp());
        final Integer connectionType = getIfNotNull(bidRequest.getDevice(), Device::getConnectiontype);

        if (app == null && connectionType == null) {
            return null;
        }

        return Client.builder()
                .deviceClass(APP_DEVICE_CLASS)
                .app(app)
                .connectionType(connectionType)
                .build();
    }

    private static org.prebid.server.rubicon.analytics.proto.App clientAppFrom(App app) {
        final ExtAppPrebid prebid = getIfNotNull(getIfNotNull(app, App::getExt), ExtApp::getPrebid);

        final org.prebid.server.rubicon.analytics.proto.App clientApp = org.prebid.server.rubicon.analytics.proto.App
                .of(
                        getIfNotNull(app, App::getBundle),
                        getIfNotNull(app, App::getVer),
                        getIfNotNull(prebid, ExtAppPrebid::getVersion),
                        getIfNotNull(prebid, ExtAppPrebid::getSource));

        return clientApp.equals(org.prebid.server.rubicon.analytics.proto.App.EMPTY) ? null : clientApp;
    }

    /**
     * Prepares event from request.
     */
    private Event.EventBuilder eventBuilderBase(HttpRequestContext httpContext, AuctionContext auctionContext) {
        final BidRequest bidRequest = auctionContext.getBidRequest();
        final Device device = bidRequest.getDevice();
        final Integer deviceLmt = getIfNotNull(device, Device::getLmt);
        final String integration = integrationFrom(auctionContext.getAccount(), bidRequest);
        final String extWrapperName = parseExtParameters(bidRequest).getWrappername();
        final String referrerUri = getIfNotNull(bidRequest.getSite(), Site::getPage);

        return Event.builder()
                .integration(integration)
                .wrapperName(extWrapperName)
                .version(pbsVersion)
                .limitAdTracking(deviceLmt != null ? deviceLmt != 0 : null)
                .eventCreator(EventCreator.of(pbsHostname, dataCenterRegion))
                .userAgent(getIfNotNull(device, Device::getUa))
                .client(clientFrom(bidRequest))
                .referrerUri(referrerUri)
                .referrerHostname(referrerHostname(referrerUri))
                .channel(channel(bidRequest))
                .user(user(httpContext, auctionContext));
    }

    private static <T, R> R getIfNotNull(T target, Function<T, R> getter) {
        return target != null ? getter.apply(target) : null;
    }

    private String integrationFrom(Account account, BidRequest bidRequest) {
        final String accountIntegrationOverride = accountIntegrationOverride(account);
        if (StringUtils.isNotBlank(accountIntegrationOverride)) {
            return accountIntegrationOverride;
        }

        final ExtRequest requestExt = bidRequest.getExt();
        final ExtRequestPrebid prebidExt = requestExt != null ? requestExt.getPrebid() : null;
        final String extIntegration = prebidExt != null ? prebidExt.getIntegration() : null;

        return StringUtils.isNotBlank(extIntegration) ? extIntegration : PBS_INTEGRATION;
    }

    private ExtRequestPrebidBiddersRubicon parseExtParameters(BidRequest bidRequest) {
        try {
            final ExtRequest extRequest = bidRequest.getExt();
            final ExtRequestPrebid prebid = extRequest == null ? null : extRequest.getPrebid();
            final ObjectNode bidders = prebid == null ? null : prebid.getBidders();
            return bidders == null
                    ? ExtRequestPrebidBiddersRubicon.EMPTY
                    : mapper.mapper().convertValue(bidders, ExtRequestPrebidBidders.class).getRubicon();
        } catch (IllegalArgumentException e) {
            return ExtRequestPrebidBiddersRubicon.EMPTY;
        }
    }

    private org.prebid.server.rubicon.analytics.proto.User user(
            HttpRequestContext httpContext, AuctionContext auctionContext) {

        final org.prebid.server.rubicon.analytics.proto.Geo geo = geo(httpContext, auctionContext);

        return geo != null ? org.prebid.server.rubicon.analytics.proto.User.of(geo) : null;
    }

    private org.prebid.server.rubicon.analytics.proto.Geo geo(HttpRequestContext httpContext,
                                                              AuctionContext auctionContext) {

        final String country = countryFrom(auctionContext.getBidRequest().getDevice(), httpContext);
        final Integer metroCode = getIfNotNull(auctionContext.getGeoInfo(), GeoInfo::getMetroNielsen);

        return country != null || metroCode != null
                ? org.prebid.server.rubicon.analytics.proto.Geo.of(country, metroCode)
                : null;
    }

    private String countryFrom(Device device, HttpRequestContext httpContext) {
        final String alpha3CountryCode = countryFrom(device);
        final String country = ObjectUtils.defaultIfNull(
                countryCodeMapper.mapToAlpha2(alpha3CountryCode),
                countryFrom(httpContext));

        return ObjectUtils.defaultIfNull(country, alpha3CountryCode); // fallback to device.geo.country if not resolved
    }

    private static String countryFrom(Device device) {
        final Geo geo = device != null ? device.getGeo() : null;
        return geo != null ? geo.getCountry() : null;
    }

    private String countryFrom(HttpRequestContext httpContext) {
        final UidAudit uidsAudit = uidsAuditCookieService.getUidsAudit(HttpUtil.cookiesAsMap(httpContext));
        return uidsAudit != null ? uidsAudit.getCountry() : null;
    }

    private static String channel(BidRequest bidRequest) {
        final String channel = StringUtils.lowerCase(channelFromRequest(bidRequest));
        return SUPPORTED_CHANNELS.contains(channel) ? channel : OTHER_CHANNEL;
    }

    /**
     * Sends event to analytics service.
     */
    private Future<Void> postEvent(Event event, MultiMap headers, boolean isDebugEnabled) {
        final String eventBody = mapper.encodeToString(event);
        if (isDebugEnabled) {
            logger.warn(String.format("Sending analytic event: %s", eventBody));
        }
        return httpClient.post(endpointUrl, headers, eventBody, 2000L)
                .compose(RubiconAnalyticsReporter::processResponse)
                .recover(RubiconAnalyticsReporter::failResponse);
    }

    /**
     * Returns headers needed for analytic request including headers fetched from {@link BidRequest}.
     */
    private MultiMap headers(Event event, BidRequest bidRequest) {
        final MultiMap headers = headers(event);

        HttpUtil.addHeaderIfValueIsNotEmpty(headers, HttpUtil.X_FORWARDED_FOR_HEADER,
                resolveIp(bidRequest.getDevice()));

        return headers;
    }

    /**
     * Returns headers needed for analytic request.
     */
    private static MultiMap headers(Event event) {
        final MultiMap headers = MultiMap.caseInsensitiveMultiMap()
                .add(HttpUtil.CONTENT_TYPE_HEADER, APPLICATION_JSON);

        HttpUtil.addHeaderIfValueIsNotEmpty(headers, HttpUtil.USER_AGENT_HEADER, event.getUserAgent());
        HttpUtil.addHeaderIfValueIsNotEmpty(headers, HttpUtil.REFERER_HEADER, event.getReferrerUri());

        return headers;
    }

    private String resolveIp(Device device) {
        final String ipv4 = getIfNotNull(device, Device::getIp);
        final String ipv6 = getIfNotNull(device, Device::getIpv6);

        final String result;
        if (StringUtils.isNotBlank(ipv4)) {
            result = ipAddressHelper.maskIpv4(ipv4);
        } else if (StringUtils.isNotBlank(ipv6)) {
            result = ipv6;
        } else {
            result = null;
        }
        return result;
    }

    /**
     * Handles {@link HttpClientResponse}, analyzes response status
     * and creates {@link Future} of {@link Void} from body content
     * or throws {@link PreBidException} in case of errors.
     */
    private static Future<Void> processResponse(HttpClientResponse response) {
        final int statusCode = response.getStatusCode();
        return statusCode < 200 || statusCode > 299
                ? Future.failedFuture(new PreBidException(String.format("HTTP status code %d", statusCode)))
                : Future.succeededFuture();
    }

    /**
     * Handles errors occurred while HTTP request or response processing.
     */
    private static Future<Void> failResponse(Throwable exception) {
        logger.warn("Error occurred while interacting with Rubicon Analytics", exception);
        return Future.failedFuture(exception);
    }

    private Integer accountSamplingFactor(Account account) {
        final ObjectNode configuration = moduleConfiguration(account);
        final JsonNode samplingFactorNode = configuration != null ? configuration.get(SAMPLING_FACTOR_FIELD) : null;

        return samplingFactorNode != null && samplingFactorNode.isInt() ? samplingFactorNode.asInt() : null;
    }

    private String accountIntegrationOverride(Account account) {
        final ObjectNode configuration = moduleConfiguration(account);
        final JsonNode integrationOverrideNode =
                configuration != null ? configuration.get(INTEGRATION_OVERRIDE_FIELD) : null;

        return integrationOverrideNode != null && integrationOverrideNode.isTextual()
                ? integrationOverrideNode.asText()
                : null;
    }

    private ObjectNode moduleConfiguration(Account account) {
        final AccountAnalyticsConfig analyticsConfig = account.getAnalytics();
        final Map<String, ObjectNode> modulesConfig = analyticsConfig != null ? analyticsConfig.getModules() : null;

        return MapUtils.getObject(modulesConfig, name());
    }

    /**
     * Holds correspondence between OpenRTB Bid and Analytics Bid.
     */
    @Value
    private static class TwinBids {

        Bid openrtbBid;

        org.prebid.server.rubicon.analytics.proto.Bid analyticsBid;
    }
}
