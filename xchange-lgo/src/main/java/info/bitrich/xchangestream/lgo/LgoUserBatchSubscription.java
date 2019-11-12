package info.bitrich.xchangestream.lgo;

import com.fasterxml.jackson.databind.ObjectMapper;
import info.bitrich.xchangestream.lgo.domain.*;
import info.bitrich.xchangestream.lgo.dto.*;
import info.bitrich.xchangestream.service.netty.StreamingObjectMapperHelper;
import io.reactivex.Observable;
import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.dto.Order;
import org.knowm.xchange.dto.trade.*;
import org.slf4j.*;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.*;

class LgoUserBatchSubscription {

    private final Observable<LgoGroupedUserUpdate> downstream;
    private static final Logger LOGGER = LoggerFactory.getLogger(LgoUserBatchSubscription.class);
    private final LgoStreamingService streamingService;
    private final CurrencyPair currencyPair;


    static LgoUserBatchSubscription create(LgoStreamingService streamingService, CurrencyPair currencyPair) {
        return new LgoUserBatchSubscription(streamingService, currencyPair);
    }

    private LgoUserBatchSubscription(LgoStreamingService streamingService, CurrencyPair currencyPair) {
        this.streamingService = streamingService;
        this.currencyPair = currencyPair;
        downstream = createSubscription();
    }

    Observable<LgoGroupedUserUpdate> getPublisher() {
        return downstream;
    }

    private Observable<LgoGroupedUserUpdate> createSubscription() {
        final ObjectMapper mapper = StreamingObjectMapperHelper.getObjectMapper();
        return streamingService
                .subscribeChannel(LgoAdapter.channelName("user", currencyPair))
                .map(s -> mapper.readValue(s.toString(), LgoUserMessage.class))
                .scan(new LgoGroupedUserUpdate(), (acc, s) -> {
                    List<LgoBatchOrderEvent> events = new ArrayList<>();
                    if (s.getType().equals("update")) {
                        if (s.getBatchId() != acc.getBatchId() + 1) {
                            LOGGER.warn("Wrong batch id. Expected {} get {}", acc.getBatchId() + 1, s.getBatchId());
                            resubscribe();
                        }
                        LgoUserUpdate userUpdate = (LgoUserUpdate) s;
                        List<Order> updates = updateAllOrders(currencyPair, userUpdate.getOrderEvents(), acc.getAllOpenOrders());
                        events.addAll(LgoAdapter.adaptOrderEvent(userUpdate.getOrderEvents(), s.getBatchId(), updates));
                        return new LgoGroupedUserUpdate(acc.getAllOpenOrders(), updates, events, s.getBatchId(), s.getType());
                    } else {
                        Collection<LimitOrder> allOrders = handleUserSnapshot(currencyPair, (LgoUserSnapshot) s);
                        ConcurrentMap<String, Order> asMap = allOrders.stream().collect(toConcurrentMap(LimitOrder::getId, this::copyOrder));
                        return new LgoGroupedUserUpdate(asMap, new ArrayList<>(allOrders), events, s.getBatchId(), s.getType());
                    }

                })
                .skip(1)
                .share();
    }

    private void resubscribe() {
        if (downstream == null) {
            return;
        }
        try {
            String channelName = LgoAdapter.channelName("user", currencyPair);
            streamingService.sendMessage(streamingService.getUnsubscribeMessage(channelName));
            streamingService.sendMessage(streamingService.getSubscribeMessage(channelName));
        } catch (IOException e) {
            LOGGER.warn("Error resubscribing", e);
        }
    }

    private List<Order> updateAllOrders(CurrencyPair currencyPair, List<LgoBatchOrderEvent> orderEvents, Map<String, Order> allOpenOrders) {
        return orderEvents.stream()
                .map(orderEvent -> orderEvent.applyOnOrders(currencyPair, allOpenOrders))
                .map(this::copyOrder)
                .collect(Collectors.toList());
    }

    private Collection<LimitOrder> handleUserSnapshot(CurrencyPair currencyPair, LgoUserSnapshot s) {
        return LgoAdapter.adaptOrdersSnapshot(s.getSnapshotData(), currencyPair);

    }

    private Order copyOrder(Order order) {
        Order copy = order instanceof LimitOrder ? LimitOrder.Builder.from(order).build() : MarketOrder.Builder.from(order).build();
        // because actual released version of xchange-core has buggy Builder.from methods
        copy.setFee(order.getFee());
        copy.setCumulativeAmount(order.getCumulativeAmount());
        // https://github.com/knowm/XChange/pull/3163
        return copy;
    }
}
