package com.github.xingjianluo.riskscenarioenginejava.api;

import com.github.xingjianluo.riskscenarioenginejava.trial.TrialMetrics;
import com.github.xingjianluo.riskscenarioenginejava.trial.TrialRequest;
import com.github.xingjianluo.riskscenarioenginejava.trial.TrialResult;
import com.github.xingjianluo.riskscenarioenginejava.trial.TrialService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/trials")
public class RiskController {

    private final TrialService trialService;

    public RiskController(TrialService trialService) {
        this.trialService = trialService;
    }

    @PostMapping
    public CompletableFuture<TrialResult> submit(@RequestBody TrialRequest request) {
        return trialService.submit(request);
    }

    @GetMapping("/metrics")
    public Map<String, Long> metrics() {
        return trialService.metrics().asMap();
    }
}
