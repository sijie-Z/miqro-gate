/**
 * PostgreSQL persistence layer.
 *
 * <p>
 * Uses Spring JDBC (NOT JPA, NOT R2DBC per ADR-0006). Flyway manages schema
 * migrations. All database access is synchronous — blocking calls are handled
 * by the control-plane's servlet container threads, not by the Gateway's
 * Reactor event loop.
 * </p>
 *
 * <h2>Migration rules</h2>
 * <ul>
 * <li>Published migrations are NEVER modified</li>
 * <li>New changes require a new versioned migration</li>
 * <li>All business timestamps use {@code timestamptz}</li>
 * <li>All tenant tables include {@code tenant_id}</li>
 * </ul>
 */
package com.miqroera.miqrokey.persistence;
