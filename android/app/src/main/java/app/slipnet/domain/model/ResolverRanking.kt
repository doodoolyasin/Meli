package app.slipnet.domain.model

/**
 * Orders scanner hosts by measured quality, so a "best first" list (the Harvested pool) really
 * starts with the best resolvers instead of the order in which probes happened to finish.
 */
object ResolverRanking {
    private const val TIER_E2E = 0
    private const val TIER_DNS_WORKING = 1
    private const val TIER_UNKNOWN = 2

    /**
     * Returns [hosts] sorted best-first: tunnel-verified (E2E) by total time, then DNS-working by
     * response time, then hosts without a result. Ties keep their input order.
     */
    fun order(hosts: List<String>, results: List<ResolverScanResult>): List<String> {
        val byHost = HashMap<String, ResolverScanResult>(results.size * 2)
        for (r in results) byHost[r.host] = r
        return hosts.withIndex()
            .sortedWith(
                compareBy<IndexedValue<String>>(
                    { tier(byHost[it.value]) },
                    { latency(byHost[it.value]) },
                    { it.index }
                )
            )
            .map { it.value }
    }

    private fun tier(r: ResolverScanResult?): Int {
        val e2e = r?.e2eTestResult
        return when {
            e2e != null && e2e.success -> TIER_E2E
            r != null && r.status == ResolverStatus.WORKING -> TIER_DNS_WORKING
            else -> TIER_UNKNOWN
        }
    }

    private fun latency(r: ResolverScanResult?): Long {
        val e2e = r?.e2eTestResult
        if (e2e != null && e2e.success) return e2e.totalMs
        return r?.responseTimeMs ?: Long.MAX_VALUE
    }
}
