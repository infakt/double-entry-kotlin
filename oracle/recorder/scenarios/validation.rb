# frozen_string_literal: true

# spec/double_entry/validation/line_check_spec.rb: LineCheck and
# AccountFixer, on the generator's tables without json_metadata.
#
# "A user with 100 dollars" (line_check_spec.rb:31) is the factory's
# transfer of 100_00 from test to savings, code bonus, for User 1
# (spec/support/factories.rb:16-21); "a non default currency balance"
# (:97) is its transfer from btc_test to btc_savings, here the BHD accounts
# bhd_test and bhd_savings (X-02). Either way the credit is line 1, on the
# from account, and the debit line 2. sql_update corrupts the first line or
# balance record by id, as the spec's update_all does (:55, :74, :100).
#
# The line-check scenarios run line_check with fixer false, the
# account-fixer ones with fixer true, as the spec's subject does
# (line_check_spec.rb:28). The check, its errors_found and its log are the
# same either way; the fixer only changes the balances afterwards. The log's
# inspect lines of an 'Error on line' entry are masked (oracle/README.md,
# "Line checks").

validation_schema = { shape: 'generator', json_metadata: false }
spec = 'spec/double_entry/validation/line_check_spec.rb'

clock = Time.iso8601(Oracle::Definitions::DEFAULT_CLOCK)
# The ISO 8601 UTC string of the clock moved by `minutes`.
minutes_from_now = ->(minutes) { (clock + (minutes * 60)).utc.iso8601 }

user = { user: 1 }
test = { identifier: 'test', scope: user }
savings = { identifier: 'savings', scope: user }
bhd_test = { identifier: 'bhd_test', scope: user }
bhd_savings = { identifier: 'bhd_savings', scope: user }

# line_check_spec.rb:31, create(:user, savings_balance: Money.new(100_00)).
user_with_100_dollars = lambda do
  define_account identifier: 'savings', scope: 'user_id', positive_only: true
  define_account identifier: 'test', scope: 'user_id'
  define_transfer from: 'test', to: 'savings', code: 'bonus'
  transfer amount: 100_00, from: test, to: savings, code: 'bonus'
end

# line_check_spec.rb:97, create(:user, bitcoin_balance: Money.new(100_00, 'BTC')).
user_with_bhd = lambda do
  define_account identifier: 'bhd_test', scope: 'user_id', currency: 'BHD'
  define_account identifier: 'bhd_savings', scope: 'user_id', currency: 'BHD'
  define_transfer from: 'bhd_test', to: 'bhd_savings', code: 'bhd_test_transfer'
  transfer amount: 100_00, currency: 'BHD', from: bhd_test, to: bhd_savings, code: 'bhd_test_transfer'
end

# line-check: LineCheck.last_line_id_checked and perform! without a fixer.

scenario 'line-check-last-line-id',
         area: 'line-check',
         source: "#{spec}:21",
         schema: validation_schema,
         description: 'last_line_id_checked is the last_line_id of the newest check by created_at, ' \
                      'not the last one created.' do
  travel to: minutes_from_now.call(-3)
  create_line_check last_line_id: 100, errors_found: false, log: ''
  travel to: minutes_from_now.call(-1)
  create_line_check last_line_id: 300, errors_found: false, log: ''
  travel to: minutes_from_now.call(-2)
  create_line_check last_line_id: 200, errors_found: false, log: ''
  travel to: minutes_from_now.call(0)
  last_line_id_checked
end

scenario 'line-check-consistent',
         area: 'line-check',
         source: ["#{spec}:45", "#{spec}:46", "#{spec}:48"],
         schema: validation_schema,
         description: 'A check of consistent lines persists a LineCheck without errors, ' \
                      'up to the last line, with an empty log.' do
  instance_exec(&user_with_100_dollars)
  line_check fixer: false
end

scenario 'line-check-nothing-new',
         area: 'line-check',
         source: ["#{spec}:37", "#{spec}:39"],
         schema: validation_schema,
         description: 'A check with no lines since the last check returns nothing and persists no LineCheck.' do
  instance_exec(&user_with_100_dollars)
  line_check fixer: false
  line_check fixer: false
end

scenario 'line-check-line-error',
         area: 'line-check',
         source: ["#{spec}:57", "#{spec}:58", "#{spec}:67"],
         schema: validation_schema,
         description: 'A line whose balance is not the previous balance plus its amount is an error on the line ' \
                      'and on its account; without a fixer the balance stays wrong.' do
  instance_exec(&user_with_100_dollars)
  sql_update table: 'double_entry_lines', delta: 1
  line_check fixer: false
  line_attr line: 1, attr: 'balance'
end

scenario 'line-check-balance-error',
         area: 'line-check',
         source: ["#{spec}:76", "#{spec}:78", "#{spec}:89"],
         schema: validation_schema,
         description: 'A balance record that differs from the running balance is an error on the account; ' \
                      'without a fixer the record stays wrong.' do
  instance_exec(&user_with_100_dollars)
  sql_update table: 'double_entry_account_balances', delta: 1
  line_check fixer: false
  account_balance_record(**savings)
  account_balance_record(**test)
end

scenario 'line-check-other-currency',
         area: 'line-check',
         source: "#{spec}:98",
         schema: validation_schema,
         description: 'A check of consistent lines in another currency than the default finds no errors.' do
  instance_exec(&user_with_bhd)
  line_check fixer: false
end

scenario 'line-check-other-currency-line-error',
         area: 'line-check',
         source: ["#{spec}:102", "#{spec}:104", "#{spec}:115"],
         schema: validation_schema,
         description: 'A wrong line balance in another currency is an error on the line and on its account, ' \
                      'logged in that currency; without a fixer the balance stays wrong.' do
  instance_exec(&user_with_bhd)
  sql_update table: 'double_entry_lines', delta: 1
  line_check fixer: false
  line_attr line: 1, attr: 'balance'
end

# account-fixer: perform!(fixer: AccountFixer.new) recalculates the accounts
# with errors.

scenario 'account-fixer-line-error',
         area: 'account-fixer',
         source: ["#{spec}:57", "#{spec}:58", "#{spec}:60"],
         schema: validation_schema,
         description: 'With a fixer, a check that finds a wrong line balance recalculates the account\'s ' \
                      'running balances and its balance record.' do
  instance_exec(&user_with_100_dollars)
  sql_update table: 'double_entry_lines', delta: 1
  line_check fixer: true
  line_attr line: 1, attr: 'balance'
end

scenario 'account-fixer-balance-error',
         area: 'account-fixer',
         source: ["#{spec}:76", "#{spec}:78", "#{spec}:82"],
         schema: validation_schema,
         description: 'With a fixer, a check that finds a wrong balance record sets it to the recalculated balance.' do
  instance_exec(&user_with_100_dollars)
  sql_update table: 'double_entry_account_balances', delta: 1
  line_check fixer: true
  account_balance_record(**savings)
  account_balance_record(**test)
end

scenario 'account-fixer-other-currency-line-error',
         area: 'account-fixer',
         source: ["#{spec}:102", "#{spec}:104", "#{spec}:108"],
         schema: validation_schema,
         description: 'With a fixer, a wrong line balance in another currency is recalculated in that currency.' do
  instance_exec(&user_with_bhd)
  sql_update table: 'double_entry_lines', delta: 1
  line_check fixer: true
  line_attr line: 1, attr: 'balance'
end
