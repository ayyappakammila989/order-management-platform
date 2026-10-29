package com.retail.oms.fraud.domain;

import java.util.List;

/** @param reasons codes of the rules that hit, in rule order */
public record Evaluation(int score, Decision decision, List<String> reasons) {
}
