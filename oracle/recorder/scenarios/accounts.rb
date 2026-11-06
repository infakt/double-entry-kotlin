# frozen_string_literal: true

# spec/double_entry/account_spec.rb and spec/double_entry_spec.rb:17-89.
# Account instances are built through DoubleEntry.account, from accounts the
# scenario defines, where account_spec.rb calls Account::Instance.new on an
# Account it builds itself. A scope is a string (passed as is, for the
# identity scope of account_spec.rb:4) or {user: n}, a User with id n.

identifier31 = 'xxxxxxxx 31 characters xxxxxxxx'
identifier32 = 'xxxxxxxx 32 characters xxxxxxxxx'
scope23 = 'xxxx 23 characters xxxx'
scope24 = 'xxxx 24 characters xxxxx'

scenario 'accounts-find-unscoped',
         area: 'accounts',
         source: %w[
           spec/double_entry_spec.rb:54
           spec/double_entry_spec.rb:78
           spec/double_entry/account_spec.rb:89
         ],
         description: 'An unscoped account is found by its identifier alone; it has no scope identity.' do
  define_account identifier: 'unscoped'
  define_account identifier: 'scoped', scope: 'user_id'
  account_attr identifier: 'unscoped', attr: 'identifier'
  account_attr identifier: 'unscoped', attr: 'scoped?'
  account identifier: 'unscoped'
end

scenario 'accounts-find-scoped',
         area: 'accounts',
         source: %w[
           spec/double_entry_spec.rb:58
           spec/double_entry_spec.rb:85
           spec/double_entry/account_spec.rb:96
         ],
         description: 'A scoped account is found with a scope, whose id is its scope identity as a string, ' \
                      'or with a scope identity given as is.' do
  define_account identifier: 'unscoped'
  define_account identifier: 'scoped', scope: 'user_id'
  account_attr identifier: 'scoped', scope: { user: 1 }, attr: 'identifier'
  account_attr identifier: 'scoped', scope: { user: 1 }, attr: 'scoped?'
  account identifier: 'scoped', scope_identity: '7'
  account identifier: 'scoped', scope: { user: 1 }
end

scenario 'accounts-unknown-identifier',
         area: 'accounts',
         source: 'spec/double_entry_spec.rb:62',
         description: 'An identifier that was not defined raises UnknownAccount.' do
  define_account identifier: 'unscoped'
  define_account identifier: 'scoped', scope: 'user_id'
  account identifier: 'invalid'
end

scenario 'accounts-unscoped-with-scope',
         area: 'accounts',
         source: %w[
           spec/double_entry_spec.rb:66
           spec/double_entry/account_spec.rb:89
         ],
         description: 'Asking for an unscoped account with a scope raises UnknownAccount.' do
  define_account identifier: 'unscoped'
  define_account identifier: 'scoped', scope: 'user_id'
  account identifier: 'unscoped', scope: { user: 1 }
end

scenario 'accounts-scoped-without-scope',
         area: 'accounts',
         source: %w[
           spec/double_entry_spec.rb:70
           spec/double_entry/account_spec.rb:96
         ],
         description: 'Asking for a scoped account without a scope raises UnknownAccount.' do
  define_account identifier: 'unscoped'
  define_account identifier: 'scoped', scope: 'user_id'
  account identifier: 'scoped'
end

# account.rb:17 asks present?, so a blank scope is no scope.
scenario 'accounts-blank-scope-is-unscoped',
         area: 'accounts',
         source: %w[
           spec/double_entry_spec.rb:54
           spec/double_entry_spec.rb:70
         ],
         description: 'A blank scope or scope identity counts as none: it finds the unscoped account, ' \
                      'and a scoped account asked for with a blank scope raises UnknownAccount.' do
  define_account identifier: 'unscoped'
  define_account identifier: 'scoped', scope: 'user_id'
  account identifier: 'unscoped', scope: ''
  account identifier: 'unscoped', scope_identity: ''
  account identifier: 'scoped', scope: ''
end

scenario 'accounts-define-duplicate',
         area: 'accounts',
         source: 'spec/double_entry_spec.rb:18',
         description: 'Defining the same identifier twice raises DuplicateAccount.' do
  define_account identifier: 'gah!'
  define_account identifier: 'gah!'
end

scenario 'accounts-identifier-at-max-length',
         area: 'accounts',
         source: 'spec/double_entry/account_spec.rb:13',
         description: 'With account_identifier_max_length 31, an identifier of 31 characters is accepted.' do
  set_config key: 'account_identifier_max_length', value: 31
  define_account identifier: identifier31
end

scenario 'accounts-identifier-over-max-length',
         area: 'accounts',
         source: 'spec/double_entry/account_spec.rb:20',
         description: 'With account_identifier_max_length 31, an identifier of 32 characters ' \
                      'raises AccountIdentifierTooLongError.' do
  set_config key: 'account_identifier_max_length', value: 31
  define_account identifier: identifier32
end

scenario 'accounts-scope-at-max-length',
         area: 'accounts',
         source: 'spec/double_entry/account_spec.rb:55',
         description: 'With scope_identifier_max_length 23, a scope identity of 23 characters is accepted.' do
  set_config key: 'scope_identifier_max_length', value: 23
  define_account identifier: 'x', scope: 'identity'
  account identifier: 'x', scope: scope23
end

scenario 'accounts-scope-over-max-length',
         area: 'accounts',
         source: 'spec/double_entry/account_spec.rb:60',
         description: 'With scope_identifier_max_length 23, a scope identity of 24 characters ' \
                      'raises ScopeIdentifierTooLongError.' do
  set_config key: 'scope_identifier_max_length', value: 23
  define_account identifier: 'x', scope: 'identity'
  account identifier: 'x', scope: scope24
end

scenario 'accounts-sort-order',
         area: 'accounts',
         source: 'spec/double_entry/account_spec.rb:28',
         description: 'Account instances sort by scope identity, then identifier, both as strings; ' \
                      'an unscoped account sorts as an empty scope identity.' do
  define_account identifier: 'savings', scope: 'identity'
  define_account identifier: 'checking', scope: 'identity'
  define_account identifier: 'cash'
  sort_accounts accounts: [
    { identifier: 'savings', scope: '456' },
    { identifier: 'savings', scope: '123' },
    { identifier: 'checking', scope: '456' },
    { identifier: 'cash' },
  ]
end

scenario 'accounts-equality-and-hash',
         area: 'accounts',
         source: 'spec/double_entry/account_spec.rb:35',
         description: 'Two instances of an account with the same scope are equal and hash alike; ' \
                      'with different scopes they are neither.' do
  define_account identifier: 'savings', scope: 'identity'
  accounts_equal left: { identifier: 'savings', scope: '123' }, right: { identifier: 'savings', scope: '123' }
  accounts_equal left: { identifier: 'savings', scope: '123' }, right: { identifier: 'savings', scope: '456' }
  hash_equal left: { identifier: 'savings', scope: '123' }, right: { identifier: 'savings', scope: '123' }
  hash_equal left: { identifier: 'savings', scope: '123' }, right: { identifier: 'savings', scope: '456' }
end

scenario 'accounts-default-currency',
         area: 'accounts',
         source: 'spec/double_entry/account_spec.rb:67',
         description: 'An account defined without a currency has Money.default_currency, USD.' do
  define_account identifier: 'savings', scope: 'identity'
  account_attr identifier: 'savings', scope: '123', attr: 'currency'
end

scenario 'accounts-explicit-currency',
         area: 'accounts',
         source: 'spec/double_entry/account_spec.rb:72',
         description: 'An account defined with currency AUD has AUD.' do
  define_account identifier: 'savings', scope: 'identity', currency: 'AUD'
  account_attr identifier: 'savings', scope: '123', attr: 'currency'
end

# Account.currency (account.rb:22-24) looks the definition up without a scope:
# the currency of a scoped or an unscoped account, or nil for an identifier no
# account has. BHD stands in for BTC (X-02).
scenario 'accounts-currency-by-identifier',
         area: 'accounts',
         source: %w[
           spec/double_entry/account_spec.rb:67
           spec/double_entry/account_spec.rb:72
         ],
         description: 'Account.currency gives the currency of the account defined with an identifier, ' \
                      'scoped or not: USD, the default, for one defined without a currency, BHD for one ' \
                      'defined with it; an identifier no account has gives nil.' do
  define_account identifier: 'cash'
  define_account identifier: 'bhd_savings', scope: 'identity', currency: 'BHD'
  account_currency identifier: 'cash'
  account_currency identifier: 'bhd_savings'
  account_currency identifier: 'invalid'
end

# The lambda of double_entry_spec.rb:355-358 raises for a scope that is not a
# User, when the instance is built (account.rb:131).
scenario 'accounts-scope-identifier-raises',
         area: 'accounts',
         source: 'spec/double_entry_spec.rb:403',
         description: 'A scope_identifier that raises makes DoubleEntry.account raise the same error.' do
  define_account identifier: 'savings', scope: 'user_only'
  account_attr identifier: 'savings', scope: { user: 7 }, attr: 'scope_identity'
  account identifier: 'savings', scope: '7'
end

# Account::Instance#to_s (account.rb:118), the text line-check's log writes.
scenario 'accounts-to-string',
         area: 'accounts',
         source: 'spec/double_entry/validation/line_check_spec.rb:82',
         description: 'to_s writes the identifier, the scope as given and the currency.' do
  define_account identifier: 'cash'
  define_account identifier: 'savings', scope: 'identity', currency: 'AUD'
  account_to_s identifier: 'cash'
  account_to_s identifier: 'savings', scope: '123'
end
