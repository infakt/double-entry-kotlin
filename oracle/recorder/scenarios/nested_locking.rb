# frozen_string_literal: true

# spec/double_entry/locking_spec.rb:93-178: transfers and nested locks inside
# a lock, rollbacks, the transactional-fixtures allowance
# (lib/double_entry/locking.rb:116), and how lock_accounts handles a
# StatementInvalid. The accounts are those of locking_spec.rb:16-38, as in
# recorder/scenarios/locking.rb. The concurrent examples (locking_spec.rb:181-213)
# are not recorded: SQLite cannot run them, and the concurrency stage covers
# them on PostgreSQL.
#
# locking_spec.rb:155 and 168 stub with_restart_on_deadlock to raise; here
# inject_error raises from the body it wraps instead, so the gem's own
# rescue runs too (see Injections in recorder/injections.rb).

locking_schema = { shape: 'generator', json_metadata: false }

locking_accounts = lambda do
  %w[account_a account_b account_c account_d].each do |identifier|
    define_account identifier: identifier, scope: 'identity'
  end
  define_account identifier: 'account_e'
  define_transfer from: 'account_a', to: 'account_b', code: 'test'
  define_transfer from: 'account_c', to: 'account_d', code: 'test'
end

account_a = { identifier: 'account_a', scope: '1' }
account_b = { identifier: 'account_b', scope: '2' }
account_c = { identifier: 'account_c', scope: '3' }
account_d = { identifier: 'account_d', scope: '4' }
account_e = { identifier: 'account_e' }

scenario 'nested-locking-transfer-inside-lock',
         area: 'nested-locking',
         source: 'spec/double_entry/locking_spec.rb:93',
         schema: locking_schema,
         description: 'A transfer inside a lock of both its accounts goes through.' do
  instance_exec(&locking_accounts)
  lock_accounts accounts: [account_a, account_b] do
    transfer amount: 1000, from: account_a, to: account_b, code: 'test'
  end
end

scenario 'nested-locking-transfer-lock-not-held',
         area: 'nested-locking',
         source: 'spec/double_entry/locking_spec.rb:101',
         schema: locking_schema,
         description: 'A transfer inside a lock that does not hold its destination raises LockNotHeld.' do
  instance_exec(&locking_accounts)
  lock_accounts accounts: [account_a, account_c] do
    transfer amount: 1000, from: account_a, to: account_b, code: 'test'
  end
end

scenario 'nested-locking-nested-covered',
         area: 'nested-locking',
         source: 'spec/double_entry/locking_spec.rb:109',
         schema: locking_schema,
         description: 'A nested lock of accounts the outer lock holds goes through.' do
  instance_exec(&locking_accounts)
  lock_accounts accounts: [account_a, account_b] do
    lock_accounts(accounts: [account_a, account_b]) {}
  end
end

scenario 'nested-locking-nested-not-covered',
         area: 'nested-locking',
         source: 'spec/double_entry/locking_spec.rb:117',
         schema: locking_schema,
         description: 'A nested lock of an account the outer lock does not hold raises LockNotHeld.' do
  instance_exec(&locking_accounts)
  lock_accounts accounts: [account_a] do
    lock_accounts(accounts: [account_a, account_b]) {}
  end
end

scenario 'nested-locking-rollback',
         area: 'nested-locking',
         source: 'spec/double_entry/locking_spec.rb:125',
         schema: locking_schema,
         description: 'A transfer rolled back by ActiveRecord::Rollback inside a lock leaves both balances at zero.' do
  instance_exec(&locking_accounts)
  lock_accounts accounts: [account_a, account_b] do
    transfer amount: 1000, from: account_a, to: account_b, code: 'test'
    rollback
  end
  balance account: account_a
  balance account: account_b
end

scenario 'nested-locking-rollback-on-exception',
         area: 'nested-locking',
         source: 'spec/double_entry/locking_spec.rb:134',
         schema: locking_schema,
         description: 'An exception after a transfer inside a lock propagates, and the transfer is rolled back.' do
  instance_exec(&locking_accounts)
  lock_accounts accounts: [account_a, account_b] do
    transfer amount: 1000, from: account_a, to: account_b, code: 'test'
    raise message: 'Yeah, right'
  end
end

scenario 'nested-locking-transactional-fixtures',
         area: 'nested-locking',
         source: 'spec/double_entry/locking_spec.rb:76',
         schema: locking_schema,
         description: 'With running_inside_transactional_fixtures, a lock inside a regular transaction goes through.' do
  instance_exec(&locking_accounts)
  set_config key: 'running_inside_transactional_fixtures', value: true
  in_transaction do
    lock_accounts(accounts: [account_a, account_b]) {}
  end
end

scenario 'nested-locking-lock-wait-timeout',
         area: 'nested-locking',
         source: 'spec/double_entry/locking_spec.rb:172',
         schema: locking_schema,
         description: 'A StatementInvalid matching /lock wait timeout/ while locking raises LockWaitTimeout.' do
  instance_exec(&locking_accounts)
  inject_error target: 'with_restart_on_deadlock', message: 'lock wait timeout', times: 1
  lock_accounts(accounts: [account_d, account_e]) {}
end

scenario 'nested-locking-other-statement-error',
         area: 'nested-locking',
         source: 'spec/double_entry/locking_spec.rb:159',
         schema: locking_schema,
         description: 'Any other StatementInvalid while locking is re-raised as it is.' do
  instance_exec(&locking_accounts)
  inject_error target: 'with_restart_on_deadlock', message: 'some other error', times: 1
  lock_accounts(accounts: [account_d, account_e]) {}
end
