# frozen_string_literal: true

# spec/double_entry/locking_spec.rb:41-91 and 145-149: locking creates the
# missing account balances, from the lines, and must be the outermost
# transaction. The accounts are those of locking_spec.rb:16-38. A
# lock_accounts with an empty block locks and releases at once. The
# transfer inside a regular transaction (locking_spec.rb:85) is
# transfers-inside-transaction; the nested locks and rollbacks are left to
# nested-locking.

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
account_d = { identifier: 'account_d', scope: '4' }
account_e = { identifier: 'account_e' }

scenario 'locking-creates-missing-balance',
         area: 'locking',
         source: 'spec/double_entry/locking_spec.rb:41',
         schema: locking_schema,
         description: 'Locking an account without a balance record creates one, with a zero balance.' do
  instance_exec(&locking_accounts)
  lock_accounts(accounts: [account_a]) {}
  account_balance(**account_a)
end

scenario 'locking-balance-from-lines',
         area: 'locking',
         source: 'spec/double_entry/locking_spec.rb:51',
         schema: locking_schema,
         description: 'A balance record created by a lock takes its balance from the last line of the account.' do
  instance_exec(&locking_accounts)
  seed_line account: account_a, partner_account: account_b, amount: 300, balance: 300, code: 'test'
  seed_line account: account_a, partner_account: account_b, amount: 700, balance: 1000, code: 'test'
  lock_accounts(accounts: [account_a]) {}
  account_balance(**account_a)
end

scenario 'locking-inside-transaction',
         area: 'locking',
         source: 'spec/double_entry/locking_spec.rb:76',
         schema: locking_schema,
         description: 'Locking inside a regular transaction raises LockMustBeOutermostTransaction.' do
  instance_exec(&locking_accounts)
  in_transaction do
    lock_accounts(accounts: [account_a, account_b]) {}
  end
end

scenario 'locking-scoped-and-unscoped',
         area: 'locking',
         source: 'spec/double_entry/locking_spec.rb:145',
         schema: locking_schema,
         description: 'A scoped and an unscoped account are locked together; both get a balance record.' do
  instance_exec(&locking_accounts)
  lock_accounts(accounts: [account_d, account_e]) {}
end
