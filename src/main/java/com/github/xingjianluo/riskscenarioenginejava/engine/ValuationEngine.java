package com.github.xingjianluo.riskscenarioenginejava.engine;

import com.github.xingjianluo.riskscenarioenginejava.model.Holding;
import com.github.xingjianluo.riskscenarioenginejava.model.Portfolio;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 估值引擎（线性/一阶口径，无外部依赖）。
 *
 * <ul>
 *   <li>组合现值：V = Σ quantity<sub>i</sub> × price<sub>i</sub>，允许做多与做空，可为负；</li>
 *   <li>给定各标的收益率 r<sub>i</sub> 的组合损益：PnL = Σ quantity<sub>i</sub> × price<sub>i</sub> × r<sub>i</sub>。</li>
 * </ul>
 *
 * <p>价格完整性由输入校验层保证，本引擎假定所有持仓标的在 latestPrices 中均有正价格。</p>
 */
@Component
public class ValuationEngine {

    /** 组合当前市值（多空净值之和）。 */
    public double currentValue(Portfolio portfolio, Map<String, Double> latestPrices) {
        double value = 0.0;
        for (Holding holding : portfolio.holdings()) {
            double price = latestPrices.get(holding.instrumentId());
            value += holding.quantity() * price;
        }
        return value;
    }

    /** 单期收益率冲击下的组合损益（损失取反由统计层处理）。 */
    public double pnlForReturns(Portfolio portfolio,
                                Map<String, Double> latestPrices,
                                Map<String, Double> returnsByInstrument) {
        double pnl = 0.0;
        for (Holding holding : portfolio.holdings()) {
            double price = latestPrices.get(holding.instrumentId());
            Double r = returnsByInstrument.get(holding.instrumentId());
            double ret = r == null ? 0.0 : r;
            pnl += holding.quantity() * price * ret;
        }
        return pnl;
    }
}
