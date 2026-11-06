# frozen_string_literal: true

# spec/double_entry_spec.rb:91-164, spec/double_entry/transfer_spec.rb:25-48
# and the transfer of locking_spec.rb:85. Amounts are cents of USD, the
# default currency; the BTC account of double_entry_spec.rb:98 is a BHD one.
# The checks of Transfer.transfer and Transfer#process that no example
# names (a negative or zero amount, identical accounts, the detail) take
# the nearest example as their source.

transfers_schema = { shape: 'generator', json_metadata: false }

# double_entry_spec.rb:92-106
transfer_accounts = lambda do
  define_account identifier: 'savings'
  define_account identifier: 'cash'
  define_account identifier: 'trash'
  define_account identifier: 'bitbucket', currency: 'BHD'
  define_transfer from: 'savings', to: 'cash', code: 'xfer'
  define_transfer from: 'trash', to: 'bitbucket', code: 'mismatch_xfer'
end

# double_entry_spec.rb:352-370
scoped_accounts = lambda do
  define_account identifier: 'bank'
  define_account identifier: 'cash', scope: 'user_only'
  define_account identifier: 'savings', scope: 'user_only'
  define_transfer from: 'bank', to: 'cash', code: 'xfer'
  define_transfer from: 'cash', to: 'cash', code: 'xfer'
  define_transfer from: 'cash', to: 'savings', code: 'xfer'
end

savings = { identifier: 'savings' }
cash = { identifier: 'cash' }
trash = { identifier: 'trash' }
bitbucket = { identifier: 'bitbucket' }

scenario 'transfers-allowed',
         area: 'transfers',
         source: 'spec/double_entry_spec.rb:113',
         schema: transfers_schema,
         description: 'An allowed transfer writes a credit and a debit line and the two account balances.' do
  instance_exec(&transfer_accounts)
  transfer amount: 10_000, from: savings, to: cash, code: 'xfer'
end

scenario 'transfers-wrong-direction',
         area: 'transfers',
         source: 'spec/double_entry_spec.rb:122',
         schema: transfers_schema,
         description: 'A transfer against the direction of its rule raises TransferNotAllowed and writes nothing.' do
  instance_exec(&transfer_accounts)
  transfer amount: 10_000, from: cash, to: savings, code: 'xfer'
end

scenario 'transfers-wrong-code',
         area: 'transfers',
         source: 'spec/double_entry_spec.rb:133',
         schema: transfers_schema,
         description: 'A transfer with a code no rule has raises TransferNotAllowed.' do
  instance_exec(&transfer_accounts)
  transfer amount: 10_000, from: savings, to: cash, code: 'yfer'
end

scenario 'transfers-without-code',
         area: 'transfers',
         source: 'spec/double_entry_spec.rb:144',
         schema: transfers_schema,
         description: 'A transfer without a code between accounts no rule joins raises TransferNotAllowed.' do
  instance_exec(&transfer_accounts)
  transfer amount: 10_000, from: cash, to: trash
end

scenario 'transfers-mismatched-currencies',
         area: 'transfers',
         source: 'spec/double_entry_spec.rb:154',
         schema: transfers_schema,
         description: 'An allowed transfer between accounts of different currencies raises MismatchedCurrencies.' do
  instance_exec(&transfer_accounts)
  transfer amount: 10_000, from: trash, to: bitbucket, code: 'mismatch_xfer'
end

scenario 'transfers-negative-amount',
         area: 'transfers',
         source: 'spec/double_entry_spec.rb:113',
         schema: transfers_schema,
         description: 'A negative amount raises TransferIsNegative before the rule is looked up.' do
  instance_exec(&transfer_accounts)
  transfer amount: -1, from: savings, to: cash, code: 'xfer'
end

scenario 'transfers-zero-amount',
         area: 'transfers',
         source: 'spec/double_entry_spec.rb:113',
         schema: transfers_schema,
         description: 'A transfer of zero, an amount Transfer.transfer checks only for being negative.' do
  instance_exec(&transfer_accounts)
  transfer amount: 0, from: savings, to: cash, code: 'xfer'
end

scenario 'transfers-identical-account',
         area: 'transfers',
         source: 'spec/double_entry_spec.rb:415',
         schema: transfers_schema,
         description: 'A transfer from an account to itself, with the same scope, raises TransferNotAllowed ' \
                      'even when a rule allows it.' do
  instance_exec(&scoped_accounts)
  transfer amount: 1000, from: { identifier: 'cash', scope: { user: 2 } },
           to: { identifier: 'cash', scope: { user: 2 } }, code: 'xfer'
end

scenario 'transfers-same-identifier-other-scope',
         area: 'transfers',
         source: 'spec/double_entry_spec.rb:391',
         schema: transfers_schema,
         description: 'A transfer between two scopes of the same account is allowed.' do
  instance_exec(&scoped_accounts)
  transfer amount: 1000, from: { identifier: 'cash', scope: { user: 2 } },
           to: { identifier: 'cash', scope: { user: 1 } }, code: 'xfer'
end

scenario 'transfers-returns-credit-and-debit',
         area: 'transfers',
         source: %w[
           spec/double_entry/transfer_spec.rb:37
           spec/double_entry/transfer_spec.rb:41
         ],
         schema: transfers_schema,
         description: 'A transfer creates two lines and returns the credit, the negative amount, ' \
                      'then the debit, the positive amount.' do
  # spec/support/accounts.rb:7-9, 18
  define_account identifier: 'savings', scope: 'user_id', positive_only: true
  define_account identifier: 'test', scope: 'user_id'
  define_transfer from: 'test', to: 'savings', code: 'bonus'
  transfer amount: 1000, from: { identifier: 'test', scope: { user: 1 } },
           to: { identifier: 'savings', scope: { user: 1 } }, code: 'bonus'
end

scenario 'transfers-with-detail',
         area: 'transfers',
         source: 'spec/double_entry_spec.rb:113',
         schema: transfers_schema,
         description: 'A transfer with a detail writes its type and id on both lines.' do
  instance_exec(&transfer_accounts)
  transfer amount: 10_000, from: savings, to: cash, code: 'xfer', detail: { invoice: 7 }
end

# U-05: an account given currency: 'USD' keeps the String, one without a
# currency takes the Money::Currency of Money.default_currency (account.rb:148),
# and Transfer#process compares them with != (transfer.rb:88). One scenario
# per direction, since the comparison is the to account's currency first.
currency_accounts = lambda do
  define_account identifier: 'explicit', currency: 'USD'
  define_account identifier: 'default'
  define_transfer from: 'explicit', to: 'default', code: 'xfer'
  define_transfer from: 'default', to: 'explicit', code: 'xfer'
end

scenario 'transfers-explicit-currency-to-default',
         area: 'transfers',
         source: 'spec/double_entry_spec.rb:154',
         schema: transfers_schema,
         description: 'A transfer from an account given currency \'USD\' to one defaulting to USD (U-05).' do
  instance_exec(&currency_accounts)
  transfer amount: 1_000, from: { identifier: 'explicit' }, to: { identifier: 'default' }, code: 'xfer'
end

scenario 'transfers-default-currency-to-explicit',
         area: 'transfers',
         source: 'spec/double_entry_spec.rb:154',
         schema: transfers_schema,
         description: 'A transfer from an account defaulting to USD to one given currency \'USD\' (U-05).' do
  instance_exec(&currency_accounts)
  transfer amount: 1_000, from: { identifier: 'default' }, to: { identifier: 'explicit' }, code: 'xfer'
end

scenario 'transfers-inside-transaction',
         area: 'transfers',
         source: 'spec/double_entry/locking_spec.rb:85',
         schema: transfers_schema,
         description: 'A transfer inside a regular transaction raises LockMustBeOutermostTransaction ' \
                      'and writes nothing.' do
  # locking_spec.rb:16-38, the two accounts the transfer uses
  define_account identifier: 'account_a', scope: 'identity'
  define_account identifier: 'account_b', scope: 'identity'
  define_transfer from: 'account_a', to: 'account_b', code: 'test'
  in_transaction do
    transfer amount: 1000, from: { identifier: 'account_a', scope: '1' },
             to: { identifier: 'account_b', scope: '2' }, code: 'test'
  end
end
