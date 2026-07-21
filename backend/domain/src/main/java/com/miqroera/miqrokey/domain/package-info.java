/**
 * Pure domain model for MiQroKey Gateway.
 *
 * <p>
 * This module MUST NOT depend on Spring Framework, JPA/R2DBC, Jackson, or any
 * HTTP library. It contains entity definitions, value objects, domain services,
 * and repository interfaces that are implemented by persistence modules.
 * </p>
 *
 * <h2>Dependency rules (enforced by ArchUnit)</h2>
 * <ul>
 * <li>No {@code org.springframework.*} imports</li>
 * <li>No Jakarta Persistence / Hibernate annotations</li>
 * <li>No Jackson or other serialization annotations</li>
 * <li>No HTTP or network types</li>
 * </ul>
 */
package com.miqroera.miqrokey.domain;
