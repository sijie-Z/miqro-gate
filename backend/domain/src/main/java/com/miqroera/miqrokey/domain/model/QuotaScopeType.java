package com.miqroera.miqrokey.domain.model;

/**
 * Who a quota rule applies to: an individual user or a whole project. Mirrors
 * the budget scope semantics (project) plus the Tencent/Aliyun consumer-quota
 * user dimension.
 */
public enum QuotaScopeType {
    USER, PROJECT
}
