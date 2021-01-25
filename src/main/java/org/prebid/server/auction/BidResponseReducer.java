package org.prebid.server.auction;

import com.iab.openrtb.request.Deal;
import com.iab.openrtb.request.Imp;
import com.iab.openrtb.request.Pmp;
import com.iab.openrtb.response.Bid;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.ListUtils;
import org.apache.commons.lang3.StringUtils;
import org.prebid.server.auction.model.BidderResponse;
import org.prebid.server.bidder.model.BidderBid;
import org.prebid.server.bidder.model.BidderSeatBid;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Class for removing bids from response for the same bidder-imp pair.
 */
public class BidResponseReducer {

    /**
     * Removes {@link Bid}s with the same impId taking into account if {@link Bid} has deal.
     * <p>
     * Returns given list of {@link BidderResponse}s if {@link Bid}s have different impIds.
     */
    public BidderResponse removeRedundantBids(BidderResponse bidderResponse, List<Imp> imps) {
        final List<BidderBid> bidderBids = ListUtils.emptyIfNull(bidderResponse.getSeatBid().getBids());
        final Map<String, Imp> idToImp = imps.stream().collect(Collectors.toMap(Imp::getId, Function.identity()));
        final Map<Imp, List<BidderBid>> impToBidderBids = bidderBids.stream()
                .collect(Collectors.groupingBy(bidderBid -> idToImp.get(bidderBid.getBid().getImpid())));

        final Set<BidderBid> updatedBidderBids = impToBidderBids.entrySet().stream()
                .map(impToBidders -> removeRedundantBidsForImp(impToBidders.getValue(), impToBidders.getKey()))
                .flatMap(Collection::stream)
                .collect(Collectors.toSet());

        if (bidderBids.size() == updatedBidderBids.size()) {
            return bidderResponse;
        }

        return updateBidderResponse(bidderResponse, updatedBidderBids);
    }

    private static List<BidderBid> removeRedundantBidsForImp(List<BidderBid> bidderBids, Imp imp) {
        return bidderBids.size() > 1 ? reduceBidsByImpId(bidderBids, imp) : bidderBids;
    }

    private static List<BidderBid> reduceBidsByImpId(List<BidderBid> bidderBids, Imp imp) {
        return bidderBids.stream().anyMatch(bidderBid -> bidderBid.getBid().getDealid() != null)
                ? removeRedundantDealsBids(bidderBids, imp)
                : removeRedundantForNonDealBids(bidderBids);
    }

    private static List<BidderBid> removeRedundantDealsBids(List<BidderBid> bidderBids, Imp imp) {
        final List<Deal> pgDeals = getPgDeals(imp);
        return CollectionUtils.isEmpty(pgDeals)
                ? removeRedundantForDealNonPgBids(bidderBids)
                : removeRedundantForDealPgBids(bidderBids, pgDeals);
    }

    private static List<BidderBid> removeRedundantForNonDealBids(List<BidderBid> bidderBids) {
        return Collections.singletonList(getHighestPriceBid(bidderBids, bidderBids));
    }

    private static BidderBid getHighestPriceBid(List<BidderBid> bidderBids, List<BidderBid> dealBidderBids) {
        return dealBidderBids.stream()
                .max(Comparator.comparing(bidderBid -> bidderBid.getBid().getPrice(), Comparator.naturalOrder()))
                .orElse(bidderBids.get(0));
    }

    private static BidderResponse updateBidderResponse(BidderResponse bidderResponse,
                                                       Set<BidderBid> updatedBidderBids) {

        final BidderSeatBid seatBid = bidderResponse.getSeatBid();
        final BidderSeatBid updatedSeatBid = BidderSeatBid.of(
                new ArrayList<>(updatedBidderBids),
                seatBid.getHttpCalls(),
                seatBid.getErrors());

        return BidderResponse.of(bidderResponse.getBidder(), updatedSeatBid, bidderResponse.getResponseTime());
    }

    private static List<Deal> getPgDeals(Imp imp) {
        final Pmp pmp = imp != null ? imp.getPmp() : null;
        return pmp != null ? pmp.getDeals() : Collections.emptyList();
    }

    private static List<BidderBid> removeRedundantForDealNonPgBids(List<BidderBid> bidderBids) {
        final List<BidderBid> dealBidderBids = bidderBids.stream()
                .filter(bidderBid -> StringUtils.isNotBlank(bidderBid.getBid().getDealid()))
                .collect(Collectors.toList());

        return Collections.singletonList(getHighestPriceBid(bidderBids, dealBidderBids));
    }

    private static List<BidderBid> removeRedundantForDealPgBids(List<BidderBid> bidderBids, List<Deal> pgDeals) {
        final String topDealId = getTopDealId(pgDeals, bidderBids);

        return topDealId == null ? bidderBids : bidderBids.stream()
                .filter(bidderBid -> Objects.equals(bidderBid.getBid().getDealid(), topDealId))
                .collect(Collectors.toList());
    }

    /**
     * Returns top deal id from list of {@link Deal}s.
     * <p>
     * Note: There is no guarantee {@link BidderBid}s have {@link Bid} with top deal,
     * so just find first obtained instead.
     */
    private static String getTopDealId(List<Deal> deals, List<BidderBid> bidderBids) {
        final List<String> dealIds = bidderBids.stream()
                .map(BidderBid::getBid)
                .map(Bid::getDealid)
                .collect(Collectors.toList());

        return deals.stream()
                .map(Deal::getId)
                .filter(dealIds::contains)
                .findFirst()
                .orElse(null);
    }
}
