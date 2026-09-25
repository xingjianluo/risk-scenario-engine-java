package com.github.xingjianluo.riskscenarioenginejava.domain;

import java.util.List;

public record Portfolio(String portfolioId, List<Position> positions) {
}
