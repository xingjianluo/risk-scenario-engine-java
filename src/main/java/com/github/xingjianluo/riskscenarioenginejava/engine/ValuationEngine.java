package com.github.xingjianluo.riskscenarioenginejava.engine;

import com.github.xingjianluo.riskscenarioenginejava.domain.*;
import com.github.xingjianluo.riskscenarioenginejava.validation.DataValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 估值引擎：value = Σ quantity * price(assetId)。
 * 持仓资产在快照中缺失价格时拒绝（UNKNOWN_ASSET），不静默跳过。
 */
@Component
public class ValuationEngine {

    private static final Logger log = LoggerFactory.getLogger(ValuationEngine.class);

    public double value(Portfolio portfolio, MarketSnapshot snapshot) {
        double total = 0.0;
        for (Position p : portfolio.positions()) {
            total += p.quantity() * priceOf(snapshot, p.assetId());
        }
        return total;
    }

    /** 按场景冲击后的价格估值：shockedPrice = price * (1 + shock)，未冲击资产 shock=0。 */
    public double shockedValue(Portfolio portfolio, MarketSnapshot snapshot, Map<String, Double> shocks) {
        double total = 0.0;
        for (Position p : portfolio.positions()) {
            double price = priceOf(snapshot, p.assetId());
            double shock = shocks.getOrDefault(p.assetId(), 0.0);
            total += p.quantity() * price * (1.0 + shock);
        }
        return total;
    }

    private double priceOf(MarketSnapshot snapshot, String assetId) {
        Double price = snapshot.prices().get(assetId);
        if (price == null) {
            log.info("valuation rejected: reason={} asset={} asOf={}",
                    RejectionReason.UNKNOWN_ASSET, assetId, snapshot.asOfDate());
            throw new DataValidationException(RejectionReason.UNKNOWN_ASSET,
                    "no price for asset " + assetId + " in snapshot " + snapshot.asOfDate());
        }
        return price;
    }
}
