# frozen_string_literal: true

# spec/active_record/locking_extensions_spec.rb: restartable_transaction,
# with_restart_on_deadlock and create_ignoring_duplicates!, on the users
# table the recorder adds (UserRecord, recorder/injections.rb). Every
# scenario records the gem's notifications as effects.events.
#
# Where the spec makes User.create! raise, inject_error targets create!;
# where it raises inside with_restart_on_deadlock, the deadlock-restart
# scenarios go through lock_accounts, whose lock_and_call wraps grab_locks
# in with_restart_on_deadlock inside a restartable_transaction
# (lib/double_entry/locking.rb:129-130). The restart then shows as the
# deadlock_restart event and the lock going through. The shared examples of
# each adapter context are one scenario per context. The injected messages
# are the spec's (locking_extensions_spec.rb:4-6) and are recorded exactly;
# the duplicate's RecordNotUnique comes from SQLite, so its message is
# "<engine>".

locking_schema = { shape: 'generator', json_metadata: false }
spec = 'spec/active_record/locking_extensions_spec.rb'

locking_accounts = lambda do
  %w[account_a account_b account_c account_d].each do |identifier|
    define_account identifier: identifier, scope: 'identity'
  end
  define_account identifier: 'account_e'
  define_transfer from: 'account_a', to: 'account_b', code: 'test'
  define_transfer from: 'account_c', to: 'account_d', code: 'test'
end

account_d = { identifier: 'account_d', scope: '4' }
account_e = { identifier: 'account_e' }

deadlocks = {
  'postgres' => 'PG::Error: ERROR:  deadlock detected',
  'mysql' => 'Mysql::Error: Deadlock found when trying to get lock',
  'sqlite' => 'SQLite3::BusyException: database is locked: UPDATE...',
}

scenario 'locking-extensions-restartable-transaction',
         area: 'locking-extensions',
         source: "#{spec}:9",
         events: true,
         description: 'restartable_transaction runs its block again while it raises RestartTransaction.' do
  inject_error target: 'create!', error: 'ActiveRecord::RestartTransaction',
               message: 'ActiveRecord::RestartTransaction', times: 2
  restartable_transaction do
    create_ignoring_duplicates username: 'user_a'
  end
end

deadlocks.each do |adapter, message|
  scenario "locking-extensions-deadlock-restart-#{adapter}",
           area: 'locking-extensions',
           source: ["#{spec}:20", "#{spec}:25"],
           schema: locking_schema,
           events: true,
           description: "A #{adapter} deadlock inside with_restart_on_deadlock publishes deadlock_restart " \
                        'and restarts the transaction, so the lock goes through.' do
    instance_exec(&locking_accounts)
    inject_error target: 'with_restart_on_deadlock', message: message, times: 1
    lock_accounts(accounts: [account_d, account_e]) {}
  end
end

scenario 'locking-extensions-other-error-reraised',
         area: 'locking-extensions',
         source: ["#{spec}:53", "#{spec}:71"],
         events: true,
         description: 'create_ignoring_duplicates! re-raises a StatementInvalid that is neither a duplicate ' \
                      'nor a deadlock, and publishes nothing.' do
  inject_error target: 'create!', message: 'some other error', times: 1
  create_ignoring_duplicates username: 'user_a'
end

scenario 'locking-extensions-ignore-duplicate',
         area: 'locking-extensions',
         source: ["#{spec}:53", "#{spec}:60"],
         events: true,
         description: 'create_ignoring_duplicates! ignores the RecordNotUnique of a duplicate username ' \
                      'and publishes duplicate_ignore.' do
  create_ignoring_duplicates username: 'user_a'
  create_ignoring_duplicates username: 'user_a'
end

deadlocks.each do |adapter, message|
  scenario "locking-extensions-deadlock-retry-#{adapter}",
           area: 'locking-extensions',
           source: ["#{spec}:71", "#{spec}:78"],
           events: true,
           description: "create_ignoring_duplicates! retries the create on a #{adapter} deadlock, " \
                        'publishing deadlock_retry on each retry.' do
    inject_error target: 'create!', message: message, times: 2
    create_ignoring_duplicates username: 'user_a'
  end
end
