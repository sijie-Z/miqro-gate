-- V52: usage_event.client_ip — calling-party address per usage fact (#605).
--
-- Recorded by the gateway from the transport peer, or from X-Forwarded-For
-- when the direct peer is a configured trusted proxy (rightmost non-trusted
-- hop wins; MIQROKEY_TRUSTED_PROXY_CIDRS). varchar(45) fits IPv6 literals.
-- Nullable: historical rows and requests without a resolvable peer keep null.
ALTER TABLE usage_event ADD COLUMN client_ip varchar(45);
