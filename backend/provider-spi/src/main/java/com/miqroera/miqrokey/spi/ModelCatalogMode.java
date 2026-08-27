package com.miqroera.miqrokey.spi;

/**
 * How a provider product's model catalog is obtained.
 */
public enum ModelCatalogMode {

    /** Model list is fetched from the provider's official API. */
    OFFICIAL_API,

    /** Model list is maintained manually (catalog data, admin curation). */
    MANUAL,
}
