package pl.infakt.doubleentry.parity

/** The executor of each area of oracle/enabled.json the port can replay. An area that is on needs one here. */
object Areas {
    val executors: Map<String, AreaExecutor> =
        mapOf(
            "account-balances" to AccountBalancesExecutor,
            "account-fixer" to AccountFixerExecutor,
            "accounts" to AccountsExecutor,
            "balances" to BalancesExecutor,
            "configuration" to ConfigurationExecutor,
            "line-check" to LineCheckExecutor,
            "lines" to LinesExecutor,
            "locking" to LockingExecutor,
            "locking-extensions" to LockingExtensionsExecutor,
            "metadata-json" to MetadataJsonExecutor,
            "metadata-table" to MetadataTableExecutor,
            "nested-locking" to NestedLockingExecutor,
            "schema" to SchemaExecutor,
            "transfer-rules" to TransferRulesExecutor,
            "transfers" to TransfersExecutor,
        )
}
