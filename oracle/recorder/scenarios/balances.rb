# frozen_string_literal: true

# spec/double_entry_spec.rb:231-430, spec/double_entry/balance_calculator_spec.rb
# and spec/double_entry/account_balance_spec.rb: the balances and the
# account-balances areas, which share the timeline of double_entry_spec.rb:261-287.
#
# The timeline's times are relative to the clock, the default
# 2024-01-01T00:00:00Z, and written as absolute ISO 8601 UTC strings: 3 weeks
# ago + 1 day is 2023-12-12, 2 weeks ago + 1 day 2023-12-19, 1 week ago + 1 day
# 2023-12-26, 1 week from now 2024-01-08. `travel` moves the frozen clock,
# and the timeline ends with the clock back at 2024-01-01. Amounts are cents;
# the BTC accounts of double_entry_spec.rb:246-247 are BHD ones. A transfer
# writes its credit line on the from account, then its debit line on the to
# account, so the timeline's lines are 1-16, two per transfer, in order.

balances_schema = { shape: 'generator', json_metadata: false }

clock = Time.iso8601(Oracle::Definitions::DEFAULT_CLOCK)
day = 24 * 60 * 60
week = 7 * day
# The ISO 8601 UTC string of the clock moved by `offset` seconds.
moment = ->(offset) { (clock + offset).utc.iso8601 }

work = { identifier: 'work' }
cash = { identifier: 'cash' }
savings = { identifier: 'savings' }
store = { identifier: 'store' }
btc_store = { identifier: 'btc_store' }
btc_wallet = { identifier: 'btc_wallet' }

# double_entry_spec.rb:239-288
timeline = lambda do
  define_account identifier: 'work'
  define_account identifier: 'cash'
  define_account identifier: 'savings'
  define_account identifier: 'store'
  define_account identifier: 'btc_store', currency: 'BHD'
  define_account identifier: 'btc_wallet', currency: 'BHD'
  define_transfer code: 'salary', from: 'work', to: 'cash'
  define_transfer code: 'xfer', from: 'cash', to: 'savings'
  define_transfer code: 'xfer', from: 'savings', to: 'cash'
  define_transfer code: 'purchase', from: 'cash', to: 'store'
  define_transfer code: 'layby', from: 'cash', to: 'store'
  define_transfer code: 'deposit', from: 'cash', to: 'store'
  define_transfer code: 'btc_ex', from: 'btc_store', to: 'btc_wallet'
  # 3 weeks ago + 1 day: lines 1-4
  travel to: moment.call(-3 * week + day)
  transfer amount: 100_000, from: work, to: cash, code: 'salary'
  transfer amount: 50_000, from: cash, to: savings, code: 'xfer'
  # 2 weeks ago + 1 day: lines 5-10
  travel to: moment.call(-2 * week + day)
  transfer amount: 20_000, from: cash, to: store, code: 'purchase'
  transfer amount: 10_000, from: cash, to: store, code: 'layby'
  transfer amount: 10_000, from: cash, to: store, code: 'deposit'
  # 1 week ago + 1 day: lines 11-14
  travel to: moment.call(-week + day)
  transfer amount: 20_000, from: savings, to: cash, code: 'xfer'
  transfer amount: 20_000, from: cash, to: store, code: 'purchase'
  # 1 week from now: lines 15-16
  travel to: moment.call(week)
  transfer amount: 20_000, currency: 'BHD', from: btc_store, to: btc_wallet, code: 'btc_ex'
  travel to: moment.call(0)
end

# double_entry_spec.rb:352-370: bank, and cash and savings scoped by a User.
# john is User 1 and ryan User 2 in every scenario, a fixed choice. The spec's
# lazy lets create ryan first in some examples (:391, :397, :421), so the
# recorded scope ids can differ from the spec's; only the labels differ.
scoped_accounts = lambda do
  define_account identifier: 'bank'
  define_account identifier: 'cash', scope: 'user_only'
  define_account identifier: 'savings', scope: 'user_only'
  define_transfer from: 'bank', to: 'cash', code: 'xfer'
  define_transfer from: 'cash', to: 'cash', code: 'xfer'
  define_transfer from: 'cash', to: 'savings', code: 'xfer'
end

bank = { identifier: 'bank' }
johns_cash = { identifier: 'cash', scope: { user: 1 } }
ryans_cash = { identifier: 'cash', scope: { user: 2 } }
ryans_savings = { identifier: 'savings', scope: { user: 2 } }

# spec/support/accounts.rb:8-9, 19 and factories.rb:24-30: a user with a
# checking balance is a transfer from their test account to their checking
# account. User 1 has $100, as account_balance_spec.rb:12 gives; User 2 has
# $50, so the search has a scope to leave out.
checking_balances = lambda do
  define_account identifier: 'checking', scope: 'user_id', positive_only: true
  define_account identifier: 'test', scope: 'user_id'
  define_transfer from: 'test', to: 'checking', code: 'pay'
  transfer amount: 10_000, from: { identifier: 'test', scope: { user: 1 } },
           to: { identifier: 'checking', scope: { user: 1 } }, code: 'pay'
  transfer amount: 5_000, from: { identifier: 'test', scope: { user: 2 } },
           to: { identifier: 'checking', scope: { user: 2 } }, code: 'pay'
end

# --- balances ---------------------------------------------------------------

scenario 'balances-initial',
         area: 'balances',
         source: %w[
           spec/double_entry_spec.rb:290
           spec/double_entry/balance_calculator_spec.rb:82
         ],
         schema: balances_schema,
         description: 'Without options a balance is the running balance of the account\'s last line, ' \
                      'even one written in the future, in the account\'s currency.' do
  instance_exec(&timeline)
  balance account: work
  balance account: cash
  balance account: savings
  balance account: store
  balance account: btc_store
  balance account: btc_wallet
end

scenario 'balances-at',
         area: 'balances',
         source: %w[
           spec/double_entry_spec.rb:319
           spec/double_entry/balance_calculator_spec.rb:31
         ],
         schema: balances_schema,
         description: 'A balance at a time is the running balance of the last line created at or before it.' do
  instance_exec(&timeline)
  # 1 week ago: the lines of 2 weeks ago + 1 day are the last ones; 10_000.
  balance account: cash, at: moment.call(-week)
  # At the time of a line, the line counts: line 3's balance, 50_000.
  balance account: cash, at: moment.call(-3 * week + day)
  # Before the first line, the balance is zero.
  balance account: cash, at: moment.call(-4 * week)
end

scenario 'balances-between',
         area: 'balances',
         source: %w[
           spec/double_entry_spec.rb:323
           spec/double_entry/balance_calculator_spec.rb:54
         ],
         schema: balances_schema,
         description: 'A balance between two times is the sum of the amounts of the lines created ' \
                      'between them, both ends included.' do
  instance_exec(&timeline)
  # 3 weeks ago to 2 weeks ago: the salary in and the transfer to savings,
  # 100_000 - 50_000 = 50_000.
  balance account: cash, from: moment.call(-3 * week), to: moment.call(-2 * week)
  # The two ends are the times of lines: they count. 50_000 - 40_000 = 10_000.
  balance account: cash, from: moment.call(-3 * week + day), to: moment.call(-2 * week + day)
end

scenario 'balances-between-future',
         area: 'balances',
         source: 'spec/double_entry_spec.rb:327',
         schema: balances_schema,
         description: 'A balance between now and a time in the future sums the lines written in the future.' do
  instance_exec(&timeline)
  balance account: btc_wallet, from: moment.call(0), to: moment.call(2 * week)
end

scenario 'balances-code',
         area: 'balances',
         source: %w[
           spec/double_entry_spec.rb:331
           spec/double_entry/balance_calculator_spec.rb:66
         ],
         schema: balances_schema,
         description: 'A balance by code is the sum of the amounts of the account\'s lines with that code.' do
  instance_exec(&timeline)
  balance account: cash, code: 'salary'
  # xfer out to savings and back in from it.
  balance account: cash, code: 'xfer'
end

scenario 'balances-codes',
         area: 'balances',
         source: %w[
           spec/double_entry_spec.rb:335
           spec/double_entry/balance_calculator_spec.rb:75
         ],
         schema: balances_schema,
         description: 'A balance by codes sums the lines with any of them; a code given beside codes ' \
                      'is one more of them.' do
  instance_exec(&timeline)
  balance account: store, codes: %w[layby deposit]
  balance account: store, code: 'purchase', codes: %w[layby]
end

scenario 'balances-running-per-line',
         area: 'balances',
         source: 'spec/double_entry_spec.rb:339',
         schema: balances_schema,
         description: 'Each line of an account holds the account\'s running balance after it.' do
  instance_exec(&timeline)
  # The lines of cash, by id: salary, xfer, purchase, layby, deposit, xfer, purchase.
  [2, 3, 5, 7, 9, 12, 13].each { |id| line_attr line: id, attr: 'balance' }
end

scenario 'balances-after-transfer',
         area: 'balances',
         source: 'spec/double_entry_spec.rb:308',
         schema: balances_schema,
         description: 'A transfer takes its amount from the origin\'s balance and adds it to the destination\'s.' do
  instance_exec(&timeline)
  balance account: savings
  balance account: cash
  transfer amount: 1_000, from: savings, to: cash, code: 'xfer'
  balance account: savings
  balance account: cash
end

scenario 'balances-at-ignores-range',
         area: 'balances',
         source: 'spec/double_entry/balance_calculator_spec.rb:41',
         schema: balances_schema,
         description: 'With at beside from and to, the balance is the running balance at that time: ' \
                      'the range is ignored and nothing is summed.' do
  instance_exec(&timeline)
  balance account: cash, at: moment.call(-week), from: moment.call(-3 * week), to: moment.call(-2 * week)
end

# between? is from && to && !at? (balance_calculator.rb:66-68): one end alone
# is no range, so nothing is summed and the balance is cash's last running
# balance, 10_000. A range from 1 week ago would sum to 0, one up to 2 weeks
# ago to 50_000.
scenario 'balances-from-or-to-alone',
         area: 'balances',
         source: %w[
           spec/double_entry_spec.rb:323
           spec/double_entry/balance_calculator_spec.rb:54
         ],
         schema: balances_schema,
         description: 'With from but no to, or to but no from, there is no range: the balance is the ' \
                      'running balance of the account\'s last line, nothing summed.' do
  instance_exec(&timeline)
  balance account: cash, from: moment.call(-week)
  balance account: cash, to: moment.call(-2 * week)
end

# A code makes calculate sum (balance_calculator.rb:21) the lines that both
# the at and the code conditions keep (balance_calculator.rb:90, 92).
scenario 'balances-at-with-codes',
         area: 'balances',
         source: %w[
           spec/double_entry/balance_calculator_spec.rb:31
           spec/double_entry/balance_calculator_spec.rb:66
           spec/double_entry/balance_calculator_spec.rb:75
         ],
         schema: balances_schema,
         description: 'With at beside a code or codes, the balance is the sum of the amounts of the lines ' \
                      'created at or before at with any of those codes, not a running balance.' do
  instance_exec(&timeline)
  # 2 weeks ago: of cash's xfer lines only line 3, -50_000; the running
  # balance then is 50_000, and every xfer line sums to -30_000.
  balance account: cash, at: moment.call(-2 * week), code: 'xfer'
  # 1 week ago: store's purchase and layby of 2 weeks ago + 1 day, 30_000;
  # the running balance then is 40_000, and without at they sum to 50_000.
  balance account: store, at: moment.call(-week), codes: %w[purchase layby]
end

scenario 'balances-by-identifier-and-scope',
         area: 'balances',
         source: 'spec/double_entry_spec.rb:397',
         schema: balances_schema,
         description: 'DoubleEntry.balance with an identifier and a scope gives the balance of that ' \
                      'account instance.' do
  instance_exec(&scoped_accounts)
  transfer amount: 1_000, from: ryans_cash, to: johns_cash, code: 'xfer'
  balance account: ryans_cash
  balance identifier: 'cash', scope: { user: 2 }
end

# U-15: lines created at times with microseconds. SQLite stores created_at as
# text with `.ffffff` when the fraction is not zero and compares the bounds as
# text; PostgreSQL compares timestamps. cash gets line 2 at `first` and line 4
# at `second`; the bounds sit a microsecond before, at and after them, and on
# the whole seconds around them.
first = '2024-01-01T00:00:00.123456Z'
second = '2024-01-01T00:00:01.654321Z'
micro_lines = lambda do
  define_account identifier: 'work'
  define_account identifier: 'cash'
  define_transfer code: 'salary', from: 'work', to: 'cash'
  travel to: first
  transfer amount: 10_000, from: work, to: cash, code: 'salary'
  travel to: second
  transfer amount: 5_000, from: work, to: cash, code: 'salary'
  travel to: moment.call(0)
end

scenario 'balances-at-microseconds',
         area: 'balances',
         source: %w[
           spec/double_entry_spec.rb:319
           spec/double_entry/balance_calculator_spec.rb:31
         ],
         schema: balances_schema,
         description: 'A balance at a time with microseconds counts a line created at or before it, ' \
                      'to the microsecond (U-15).' do
  instance_exec(&micro_lines)
  balance account: cash, at: '2024-01-01T00:00:00Z'
  balance account: cash, at: '2024-01-01T00:00:00.123455Z'
  balance account: cash, at: first
  balance account: cash, at: '2024-01-01T00:00:00.123457Z'
  balance account: cash, at: '2024-01-01T00:00:01Z'
  balance account: cash, at: '2024-01-01T00:00:01.65432Z'
  balance account: cash, at: second
  balance account: cash, at: '2024-01-01T00:00:01.654322Z'
end

scenario 'balances-between-microseconds',
         area: 'balances',
         source: %w[
           spec/double_entry_spec.rb:323
           spec/double_entry/balance_calculator_spec.rb:54
         ],
         schema: balances_schema,
         description: 'A balance between times with microseconds sums the lines created between them, ' \
                      'both ends included, to the microsecond (U-15).' do
  instance_exec(&micro_lines)
  balance account: cash, from: '2024-01-01T00:00:00Z', to: '2024-01-01T00:00:00.123455Z'
  balance account: cash, from: '2024-01-01T00:00:00Z', to: first
  balance account: cash, from: first, to: first
  balance account: cash, from: '2024-01-01T00:00:00.123457Z', to: '2024-01-01T00:00:01Z'
  balance account: cash, from: '2024-01-01T00:00:00.123457Z', to: second
  balance account: cash, from: '2024-01-01T00:00:01Z', to: '2024-01-01T00:00:01.654322Z'
  balance account: cash, from: '2024-01-01T00:00:01.654322Z', to: '2024-01-01T00:00:02Z'
end

# The DoubleEntry.balance half of double_entry_spec.rb:403: the scope is a
# String, standing in for the spec's double, and the user_only lambda raises
# for it when the instance is built (account.rb:131).
scenario 'balances-scope-identifier-raises',
         area: 'balances',
         source: 'spec/double_entry_spec.rb:403',
         schema: balances_schema,
         description: 'DoubleEntry.balance with a scope its account\'s scope_identifier rejects raises ' \
                      'the scope_identifier\'s error.' do
  define_account identifier: 'savings', scope: 'user_only'
  balance identifier: 'savings', scope: '7'
end

scenario 'balances-scoped-without-scope',
         area: 'balances',
         source: 'spec/double_entry_spec.rb:427',
         schema: balances_schema,
         description: 'DoubleEntry.balance of a scoped account without a scope raises UnknownAccount.' do
  instance_exec(&scoped_accounts)
  balance identifier: 'cash'
end

# --- account-balances -------------------------------------------------------

scenario 'account-balances-records-match',
         area: 'account-balances',
         source: %w[
           spec/double_entry_spec.rb:298
           spec/double_entry/account_balance_spec.rb:5
         ],
         schema: balances_schema,
         description: 'Each account\'s balance record holds the same balance as the account.' do
  instance_exec(&timeline)
  [work, cash, savings, store, btc_wallet].each do |account|
    account_balance_record(**account)
    balance account: account
  end
end

scenario 'account-balances-currency',
         area: 'account-balances',
         source: 'spec/double_entry_spec.rb:304',
         schema: balances_schema,
         description: 'A balance record\'s balance is in its account\'s currency.' do
  instance_exec(&timeline)
  account_balance_record(**btc_wallet)
end

scenario 'account-balances-min-balance-below',
         area: 'account-balances',
         source: 'spec/double_entry/account_balance_spec.rb:16',
         schema: balances_schema,
         description: 'A scope whose balance is above the minimum is found.' do
  instance_exec(&checking_balances)
  scopes_with_minimum_balance amount: 9_900, identifier: 'checking'
end

scenario 'account-balances-min-balance-equal',
         area: 'account-balances',
         source: 'spec/double_entry/account_balance_spec.rb:21',
         schema: balances_schema,
         description: 'A scope whose balance equals the minimum is found.' do
  instance_exec(&checking_balances)
  scopes_with_minimum_balance amount: 10_000, identifier: 'checking'
end

scenario 'account-balances-min-balance-above',
         area: 'account-balances',
         source: 'spec/double_entry/account_balance_spec.rb:26',
         schema: balances_schema,
         description: 'A scope whose balance is below the minimum is not found.' do
  instance_exec(&checking_balances)
  scopes_with_minimum_balance amount: 10_100, identifier: 'checking'
end

scenario 'account-balances-scoped-separately',
         area: 'account-balances',
         source: 'spec/double_entry_spec.rb:384',
         schema: balances_schema,
         description: 'Each scope of an account has a balance and a balance record of its own.' do
  instance_exec(&scoped_accounts)
  transfer amount: 2_000, from: bank, to: johns_cash, code: 'xfer'
  transfer amount: 1_000, from: bank, to: ryans_cash, code: 'xfer'
  account_balance_record(**johns_cash)
  account_balance_record(**ryans_cash)
  balance account: johns_cash
  balance account: ryans_cash
end

scenario 'account-balances-between-scopes',
         area: 'account-balances',
         source: 'spec/double_entry_spec.rb:391',
         schema: balances_schema,
         description: 'A transfer between two scopes of an account moves the balance from one scope to the other.' do
  instance_exec(&scoped_accounts)
  transfer amount: 1_000, from: ryans_cash, to: johns_cash, code: 'xfer'
  account_balance_record(**ryans_cash)
  account_balance_record(**johns_cash)
  balance account: ryans_cash
  balance account: johns_cash
end

scenario 'account-balances-same-owner-other-account',
         area: 'account-balances',
         source: 'spec/double_entry_spec.rb:421',
         schema: balances_schema,
         description: 'A transfer between two accounts of one scope moves the balance between them.' do
  instance_exec(&scoped_accounts)
  transfer amount: 10_000, from: ryans_cash, to: ryans_savings, code: 'xfer'
  account_balance_record(**ryans_cash)
  account_balance_record(**ryans_savings)
  balance account: ryans_cash
  balance account: ryans_savings
end
