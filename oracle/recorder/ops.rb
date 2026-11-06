# frozen_string_literal: true

# The scope class of double_entry_spec.rb:355-358, whose user_only lambda
# checks the class name: a scope {"user": n} is User.new(n).
User = Struct.new(:id) unless defined?(User)

# The detail of a transfer {"detail": {"invoice": n}}: a persisted row of a
# table the recorder adds (Database.invoice), since the generator schema has
# no model a line could point at. Line's detail is polymorphic, so the line
# records detail_type "Invoice" and detail_id n.
unless defined?(Invoice)
  class Invoice < ActiveRecord::Base
    self.table_name = 'oracle_invoices'
  end
end

module Oracle
  # The ops a scenario step may name, and how each one drives the gem.
  # See "Ops" in oracle/README.md.
  module Ops
    # The configuration keys of set_config and get_config, and the module
    # whose Configurable config holds each one.
    CONFIG_KEYS = {
      'account_identifier_max_length' => 'DoubleEntry',
      'code_max_length' => 'DoubleEntry',
      'json_metadata' => 'DoubleEntry',
      'running_inside_transactional_fixtures' => 'DoubleEntry::Locking',
      'scope_identifier_max_length' => 'DoubleEntry',
    }.freeze

    # The scope_identifier lambdas of define_account, as the gem's specs
    # write them.
    SCOPES = {
      # account_spec.rb:4, locking_spec.rb:17
      'identity' => ->(value) { value },
      # spec/support/accounts.rb:6, double_entry_spec.rb:46
      'user_id' => ->(user) { user.id },
      # double_entry_spec.rb:355-358
      'user_only' => lambda do |user|
        raise 'not a User' unless user.class.name == 'User'

        user.id
      end,
    }.freeze

    # What transfer_spec.rb:219-220 passes to Transfer::Set#find: a stand-in
    # for an account that carries only an identifier.
    AccountStub = Struct.new(:identifier)

    # The attributes account_attr may read from an Account::Instance.
    ACCOUNT_ATTRIBUTES = %w[identifier scope_identity currency scoped? positive_only negative_only].freeze

    # What line_attr may read from a line: `attr` through Line's own readers
    # (line.rb), `column` as the raw column, line[:column].
    LINE_ATTRIBUTES = %w[amount balance code account partner_account currency increase? decrease?].freeze
    LINE_COLUMNS = %w[
      id account scope code amount balance partner_id partner_account partner_scope detail_type detail_id
    ].freeze

    # The named scopes of Line that lines_scope may list (line.rb:60-61).
    LINE_SCOPES = %w[credits debits].freeze

    # op => [required arguments, optional arguments]
    ARGUMENTS = {
      'set_config' => [%w[key value], []],
      'get_config' => [%w[key], []],
      'define_account' => [%w[identifier], %w[scope positive_only negative_only currency]],
      'define_transfer' => [%w[from to code], []],
      'find_transfer' => [%w[from to code], []],
      'find_transfer!' => [%w[from to code], []],
      'account' => [%w[identifier], %w[scope scope_identity]],
      'account_attr' => [%w[identifier attr], %w[scope scope_identity]],
      'account_to_s' => [%w[identifier], %w[scope scope_identity]],
      'account_currency' => [%w[identifier], []],
      'sort_accounts' => [%w[accounts], []],
      'accounts_equal' => [%w[left right], []],
      'hash_equal' => [%w[left right], []],
      'describe_schema' => [[], []],
      'lock_accounts' => [%w[accounts steps], []],
      'in_transaction' => [%w[steps], []],
      'raise' => [%w[message], []],
      'transfer' => [%w[amount from to], %w[code detail currency metadata]],
      'seed_line' => [%w[account partner_account amount balance], %w[code]],
      'line_save' => [%w[account balance], %w[partner_account amount code]],
      'line_attr' => [%w[line], %w[attr column]],
      'line_partner' => [%w[line], []],
      'line_pair' => [%w[line], []],
      'account_balance' => [%w[identifier], %w[scope scope_identity]],
      'lines_scope' => [%w[scope], []],
      'travel' => [%w[to], []],
      'balance' => [[], %w[account identifier scope at from to code codes]],
      'account_balance_record' => [%w[identifier], %w[scope scope_identity]],
      'scopes_with_minimum_balance' => [%w[amount identifier], []],
      'rollback' => [[], []],
      'inject_error' => [%w[target message times], %w[error]],
      'restartable_transaction' => [%w[steps], []],
      'create_ignoring_duplicates' => [%w[username], []],
      'line_metadata_count' => [[], []],
      'line_metadata_of' => [%w[line], []],
      'line_check' => [%w[fixer], []],
      'create_line_check' => [%w[last_line_id errors_found log], []],
      'last_line_id_checked' => [[], []],
      'sql_update' => [%w[table delta], []],
    }.freeze

    # The tables sql_update may change, by the model that maps each one: the
    # first row by id, as line_check_spec.rb:55 and 74 corrupt a balance.
    SQL_UPDATE_TABLES = {
      'double_entry_account_balances' => 'DoubleEntry::AccountBalance',
      'double_entry_lines' => 'DoubleEntry::Line',
    }.freeze

    # The ops whose arguments are one account reference, flat in the step.
    ACCOUNT_OPS = %w[account account_attr account_to_s account_balance account_balance_record].freeze

    # The time options of balance, ISO 8601 UTC strings.
    BALANCE_TIMES = %w[at from to].freeze

    # The ops that run the steps nested in their `steps`.
    NESTING_OPS = %w[lock_accounts in_transaction restartable_transaction].freeze

    # The ops that build a line from these arguments.
    LINE_BUILDING_OPS = %w[seed_line line_save].freeze

    # The ops that take a line by id.
    LINE_OPS = %w[line_attr line_partner line_pair line_metadata_of].freeze

    module_function

    # Checks a step when its definition loads, before anything is recorded.
    def check(step)
      op = step.fetch('op')
      required, optional = ARGUMENTS.fetch(op) { raise RecorderError, "unknown op #{op}" }
      given = step.keys - ['op']
      missing = required - given
      unknown = given - required - optional
      raise RecorderError, "#{op} needs #{missing.join(', ')}" unless missing.empty?
      raise RecorderError, "#{op} does not take #{unknown.join(', ')}" unless unknown.empty?
      if step.key?('key') && !CONFIG_KEYS.key?(step['key'])
        raise RecorderError, "#{op}: unknown config key #{step['key']}"
      end
      if op == 'define_account' && step.key?('scope') && step['scope'] != 'none' && !SCOPES.key?(step['scope'])
        raise RecorderError, "#{op}: scope is none, #{SCOPES.keys.join(', ')}; not #{step['scope']}"
      end
      check_references(step)
      check_ledger(step)

      step
    end

    # The arguments of the ledger ops: locks, transactions, transfers, lines
    # and account balances.
    def check_ledger(step)
      op = step['op']
      check_nesting(step) if NESTING_OPS.include?(op)
      raise RecorderError, 'raise: message is a string' if op == 'raise' && !step['message'].is_a?(String)
      check_transfer(step) if op == 'transfer'
      check_line_building(step) if LINE_BUILDING_OPS.include?(op)
      check_line(step) if LINE_OPS.include?(op)
      check_balances(step)
      check_locking(step)
      check_validation(step)
      return unless op == 'lines_scope' && !LINE_SCOPES.include?(step['scope'])

      raise RecorderError, "lines_scope: scope is #{LINE_SCOPES.join(', ')}; not #{step['scope'].inspect}"
    end

    # The balance ops: travel {to}, balance, scopes_with_minimum_balance.
    # account_balance_record is checked as an account op.
    def check_balances(step)
      case step['op']
      when 'travel' then time('travel', 'to', step['to'])
      when 'balance' then check_balance(step)
      when 'scopes_with_minimum_balance'
        unless step['amount'].is_a?(Integer)
          raise RecorderError, 'scopes_with_minimum_balance: amount is an integer, in cents'
        end
        unless step['identifier'].is_a?(String)
          raise RecorderError, 'scopes_with_minimum_balance: identifier is a string'
        end
      end
    end

    # The locking ops: inject_error {target, message, times, error} and
    # create_ignoring_duplicates {username}. rollback has no arguments, and
    # restartable_transaction's steps are checked as nesting.
    def check_locking(step)
      case step['op']
      when 'inject_error' then check_injection(step)
      when 'create_ignoring_duplicates'
        raise RecorderError, 'create_ignoring_duplicates: username is a string' unless step['username'].is_a?(String)
      end
    end

    # The validation ops: line_check {fixer}, create_line_check {last_line_id,
    # errors_found, log} and sql_update {table, delta}. line_metadata_of is
    # checked as a line op; line_metadata_count and last_line_id_checked
    # have no arguments.
    def check_validation(step)
      case step['op']
      when 'line_check'
        raise RecorderError, 'line_check: fixer is true or false' unless [true, false].include?(step['fixer'])
      when 'create_line_check' then check_create_line_check(step)
      when 'sql_update'
        unless SQL_UPDATE_TABLES.key?(step['table'])
          raise RecorderError, "sql_update: table is #{SQL_UPDATE_TABLES.keys.join(', ')}; not #{step['table'].inspect}"
        end
        raise RecorderError, 'sql_update: delta is an integer, in cents' unless step['delta'].is_a?(Integer)
      end
    end

    def check_create_line_check(step)
      raise RecorderError, 'create_line_check: last_line_id is an integer' unless step['last_line_id'].is_a?(Integer)
      unless [true, false].include?(step['errors_found'])
        raise RecorderError, 'create_line_check: errors_found is true or false'
      end
      raise RecorderError, 'create_line_check: log is a string' unless step['log'].is_a?(String)
    end

    def check_injection(step)
      unless Injections::TARGETS.include?(step['target'])
        raise RecorderError, "inject_error: target is #{Injections::TARGETS.join(', ')}; not #{step['target'].inspect}"
      end
      raise RecorderError, 'inject_error: message is a string' unless step['message'].is_a?(String)
      raise RecorderError, 'inject_error: times is a positive integer' unless step['times'].is_a?(Integer) &&
                                                                              step['times'].positive?
      return unless step.key?('error') && !Injections::ERRORS.key?(step['error'])

      raise RecorderError, "inject_error: error is #{Injections::ERRORS.keys.join(', ')}; not #{step['error'].inspect}"
    end

    # balance takes an account reference nested in `account`
    # (Account::Instance#balance), or an `identifier` with an optional
    # `scope` flat in the step (DoubleEntry.balance); exactly one of the two.
    def check_balance(step)
      if step.key?('account') == step.key?('identifier')
        raise RecorderError, 'balance: takes account or identifier, exactly one'
      end
      if step.key?('account')
        raise RecorderError, 'balance: the scope of an account goes in its reference' if step.key?('scope')

        check_reference('balance', step['account'])
      else
        raise RecorderError, 'balance: identifier is a string' unless step['identifier'].is_a?(String)

        scope('balance', step['scope']) if step.key?('scope')
      end
      BALANCE_TIMES.each { |name| time('balance', name, step[name]) if step.key?(name) }
      raise RecorderError, 'balance: code is a string' if step.key?('code') && !step['code'].is_a?(String)
      return unless step.key?('codes') && !(step['codes'].is_a?(Array) && step['codes'].all?(String))

      raise RecorderError, 'balance: codes is an array of strings'
    end

    # An ISO 8601 UTC time, as input.clock is written: "2024-01-01T00:00:00Z".
    def time(op, name, value)
      problem = "#{op}: #{name} is an ISO 8601 UTC string, not #{value.inspect}"
      raise RecorderError, problem unless value.is_a?(String) && value.end_with?('Z')

      begin
        Time.iso8601(value)
      rescue ArgumentError
        raise RecorderError, problem
      end
    end

    # lock_accounts {accounts, steps} and in_transaction {steps}. The nested
    # steps were checked when the DSL built them; a step written by hand is
    # checked here too.
    def check_nesting(step)
      op = step['op']
      unless step['steps'].is_a?(Array) && step['steps'].all? { |each| each.is_a?(Hash) && each.key?('op') }
        raise RecorderError, "#{op}: steps is an array of steps"
      end
      step['steps'].each { |each| check(each) }
      return unless op == 'lock_accounts'
      raise RecorderError, 'lock_accounts: accounts is a non-empty array' unless step['accounts'].is_a?(Array) &&
                                                                                 !step['accounts'].empty?

      step['accounts'].each { |each| check_reference(op, each) }
    end

    def check_transfer(step)
      raise RecorderError, 'transfer: amount is an integer, in cents' unless step['amount'].is_a?(Integer)

      %w[from to].each { |side| check_reference('transfer', step[side]) }
      raise RecorderError, 'transfer: code is a string' if step.key?('code') && !step['code'].is_a?(String)
      if step.key?('currency') && !step['currency'].is_a?(String)
        raise RecorderError, 'transfer: currency is an ISO code, a string'
      end
      check_metadata(step['metadata']) if step.key?('metadata')
      return unless step.key?('detail')

      detail = step['detail']
      return if detail.is_a?(Hash) && detail.keys == ['invoice'] && detail['invoice'].is_a?(Integer)

      raise RecorderError, "transfer: detail is {\"invoice\": <integer>}, not #{detail.inspect}"
    end

    # A transfer's metadata: {"<key>": "<value>" | ["<value>", ...]}, as
    # transfer_spec.rb:84 and 152 give it.
    def check_metadata(metadata)
      problem = "transfer: metadata is an object of strings or arrays of strings, not #{metadata.inspect}"
      raise RecorderError, problem unless metadata.is_a?(Hash) && !metadata.empty?

      metadata.each_value do |value|
        next if value.is_a?(String)
        next if value.is_a?(Array) && !value.empty? && value.all?(String)

        raise RecorderError, problem
      end
    end

    # seed_line and line_save: account references, amounts in cents, and a
    # code that may be null (line_spec.rb:32-41).
    def check_line_building(step)
      op = step['op']
      %w[account partner_account].each { |name| check_reference(op, step[name]) if step.key?(name) }
      %w[amount balance].each do |name|
        raise RecorderError, "#{op}: #{name} is an integer, in cents" if step.key?(name) && !step[name].is_a?(Integer)
      end
      return unless step.key?('code') && !step['code'].nil? && !step['code'].is_a?(String)

      raise RecorderError, "#{op}: code is a string or null"
    end

    # line_attr, line_partner, line_pair: a line by id; line_attr reads one
    # `attr` or one `column`.
    def check_line(step)
      op = step['op']
      raise RecorderError, "#{op}: line is a line id, an integer" unless step['line'].is_a?(Integer)
      return unless op == 'line_attr'

      if step.key?('attr') == step.key?('column')
        raise RecorderError, 'line_attr: takes attr or column, exactly one'
      end
      if step.key?('attr') && !LINE_ATTRIBUTES.include?(step['attr'])
        raise RecorderError, "line_attr: attr is #{LINE_ATTRIBUTES.join(', ')}; not #{step['attr']}"
      end
      return unless step.key?('column') && !LINE_COLUMNS.include?(step['column'])

      raise RecorderError, "line_attr: column is #{LINE_COLUMNS.join(', ')}; not #{step['column']}"
    end

    def check_references(step)
      op = step['op']
      check_reference(op, step) if ACCOUNT_OPS.include?(op)
      if op == 'account_currency' && !step['identifier'].is_a?(String)
        raise RecorderError, 'account_currency: identifier is a string'
      end
      if op == 'account_attr' && !ACCOUNT_ATTRIBUTES.include?(step['attr'])
        raise RecorderError, "account_attr: attr is #{ACCOUNT_ATTRIBUTES.join(', ')}; not #{step['attr']}"
      end
      %w[left right].each { |side| check_reference(op, step[side]) if step.key?(side) }
      return unless op == 'sort_accounts'
      raise RecorderError, 'sort_accounts: accounts is an array' unless step['accounts'].is_a?(Array)

      step['accounts'].each { |each| check_reference(op, each) }
    end

    # An account reference: {identifier, scope | scope_identity}. A scope is
    # a string, given as is, or {"user": <integer>}, a User with that id.
    def check_reference(op, reference)
      raise RecorderError, "#{op}: an account reference is an object" unless reference.is_a?(Hash)

      keys = reference.keys - ['op']
      keys -= %w[attr] if op == 'account_attr'
      unknown = keys - %w[identifier scope scope_identity]
      raise RecorderError, "#{op}: an account reference does not take #{unknown.join(', ')}" unless unknown.empty?
      raise RecorderError, "#{op}: an account reference needs identifier" unless reference['identifier'].is_a?(String)
      if reference.key?('scope') && reference.key?('scope_identity')
        raise RecorderError, "#{op}: an account reference takes scope or scope_identity, not both"
      end
      if reference.key?('scope_identity') && !reference['scope_identity'].is_a?(String)
        raise RecorderError, "#{op}: scope_identity is a string"
      end
      scope(op, reference['scope']) if reference.key?('scope')
    end

    def scope(op, value)
      return value if value.is_a?(String)
      return ::User.new(value['user']) if value.is_a?(Hash) && value.keys == ['user'] && value['user'].is_a?(Integer)

      raise RecorderError, "#{op}: a scope is a string or {\"user\": <integer>}, not #{value.inspect}"
    end

    # Runs one step and returns its outcome, with exactly one key:
    # {"result" => ...} or {"error" => {"class", "message"}}.
    def outcome(step)
      result = run(step)
    rescue RecorderError
      raise
    rescue StandardError => e
      { 'error' => Values.error(e) }
    else
      { 'result' => Values.value(result) }
    end

    def run(step)
      case step.fetch('op')
      when 'set_config' then set_config(step)
      when 'get_config' then get_config(step)
      when 'define_account' then define_account(step)
      when 'define_transfer' then define_transfer(step)
      when 'find_transfer' then DoubleEntry.config.transfers.find(*transfer_key(step))
      when 'find_transfer!' then DoubleEntry.config.transfers.find!(*transfer_key(step))
      when 'account' then account(step)
      when 'account_attr' then account(step).public_send(step['attr'])
      when 'account_to_s' then account(step).to_s
      # Account.currency (account.rb:22-24): the definition's currency, scoped
      # or not, or nil for an identifier no account has.
      when 'account_currency' then DoubleEntry::Account.currency(symbol(step['identifier']))
      when 'sort_accounts' then step['accounts'].map { |each| account(each) }.sort
      when 'accounts_equal' then account(step['left']) == account(step['right'])
      # Only whether the two hashes are equal: Ruby's String#hash is seeded
      # per process, so the values themselves are not behaviour (U-11).
      when 'hash_equal' then account(step['left']).hash == account(step['right']).hash
      when 'describe_schema' then Database.describe
      else run_ledger(step)
      end
    end

    def run_ledger(step)
      case step.fetch('op')
      when 'lock_accounts' then lock_accounts(step)
      when 'in_transaction' then in_transaction(step)
      when 'raise' then raise RuntimeError.new(step['message']).extend(Injected)
      when 'transfer' then transfer(step)
      when 'seed_line' then DoubleEntry::Line.create!(line_attributes(step))
      when 'line_save' then DoubleEntry::Line.new(line_attributes(step)).save
      when 'line_attr' then line_attr(step)
      when 'line_partner' then line(step).partner
      when 'line_pair' then line(step).pair
      when 'account_balance' then DoubleEntry::AccountBalance.find_by_account(account(step))
      when 'lines_scope' then DoubleEntry::Line.public_send(step['scope']).order(:id).to_a
      else run_balances(step)
      end
    end

    def run_balances(step)
      case step.fetch('op')
      when 'travel' then travel(step)
      when 'balance' then balance(step)
      when 'account_balance_record' then DoubleEntry::AccountBalance.find_by_account(account(step))&.balance
      when 'scopes_with_minimum_balance' then scopes_with_minimum_balance(step)
      else run_locking(step)
      end
    end

    def run_locking(step)
      case step.fetch('op')
      # Leaves the enclosing transaction, which Active Record rolls back and
      # does not re-raise (locking_spec.rb:128).
      when 'rollback' then raise ActiveRecord::Rollback
      when 'inject_error' then inject_error(step)
      when 'restartable_transaction' then restartable_transaction(step)
      when 'create_ignoring_duplicates' then create_ignoring_duplicates(step)
      else run_validation(step)
      end
    end

    def run_validation(step)
      case step.fetch('op')
      when 'line_metadata_count' then DoubleEntry::LineMetadata.count
      # Line's reader, not a query of the table: the has_many of line.rb:59
      # is never defined (U-06), so this is the json metadata column, or
      # NoMethodError where the lines have none.
      when 'line_metadata_of' then line(step).metadata
      when 'line_check' then line_check(step)
      when 'create_line_check' then create_line_check(step)
      when 'last_line_id_checked' then DoubleEntry::Validation::LineCheck.last_line_id_checked
      when 'sql_update' then sql_update(step)
      else raise RecorderError, "unknown op #{step['op']}"
      end
    end

    # LineCheck.perform!(fixer: AccountFixer.new) with fixer true, as
    # line_check_spec.rb:28 runs it, or perform!(fixer: nil). The LineCheck
    # it persisted, or null when there were no new lines.
    def line_check(step)
      fixer = step['fixer'] ? DoubleEntry::Validation::AccountFixer.new : nil
      DoubleEntry::Validation::LineCheck.perform!(fixer: fixer)
    end

    # LineCheck.create!, as line_check_spec.rb:11-17 makes the checks; the
    # clock is moved with travel. The LineCheck.
    def create_line_check(step)
      DoubleEntry::Validation::LineCheck.create!(
        last_line_id: step['last_line_id'], errors_found: step['errors_found'], log: step['log']
      )
    end

    # Adds delta to the balance of the table's first row by id, past the
    # models, as line_check_spec.rb:55 and 74 do:
    # Model.order(:id).limit(1).update_all('balance = balance + 1'). Null:
    # the count update_all returns is not behaviour to port.
    def sql_update(step)
      model = Object.const_get(SQL_UPDATE_TABLES.fetch(step['table']))
      model.order(:id).limit(1).update_all("balance = balance + #{Integer(step['delta'])}")
      nil
    end

    # Arranges `times` errors for `target`, raised by the next calls to it:
    # with_restart_on_deadlock raises them from the body it wraps, create!
    # (UserRecord's) before it inserts (see Injections). Always null.
    def inject_error(step)
      error = step.fetch('error', Injections::ERRORS.keys.first)
      Injections.add(step['target'], error, step['message'], step['times'])
      nil
    end

    # UserRecord.restartable_transaction { <steps> }, as
    # locking_extensions_spec.rb:14 runs User.restartable_transaction. Null.
    def restartable_transaction(step)
      Database.users.restartable_transaction { run_steps(step['steps']) }
      nil
    end

    # UserRecord.create_ignoring_duplicates!(username:). Null: what it
    # returns is the record, or whatever the ignored branch's publish gives
    # (locking_extensions.rb:49), and neither is behaviour to port.
    def create_ignoring_duplicates(step)
      Database.users.create_ignoring_duplicates!(username: step['username'])
      nil
    end

    # Moves the frozen clock to `to`, as the gem's specs do with
    # Timecop.freeze. It stays there for the steps after it, until the next
    # travel; the recorder's Timecop.freeze block around the steps returns
    # it when the scenario ends. Always null.
    def travel(step)
      Timecop.freeze(Time.iso8601(step['to']))
      nil
    end

    # Account::Instance#balance(options) for a nested `account`, or
    # DoubleEntry.balance(identifier, scope:, options) for a flat
    # `identifier`: the account is found with the same options
    # (double_entry.rb:142-145). Only the options the step gives are passed.
    def balance(step)
      options = {}
      BALANCE_TIMES.each { |name| options[name.to_sym] = Time.iso8601(step[name]) if step.key?(name) }
      options[:code] = symbol(step['code']) if step.key?('code')
      options[:codes] = step['codes'].map { |each| symbol(each) } if step.key?('codes')
      return account(step['account']).balance(options) if step.key?('account')

      options[:scope] = scope('balance', step['scope']) if step.key?('scope')
      DoubleEntry.balance(symbol(step['identifier']), options)
    end

    # AccountBalance.scopes_with_minimum_balance_for_account(Money.new(amount),
    # identifier). The gem plucks the scopes in no order, so they are
    # sorted: a null scope first, then by text (U-14).
    def scopes_with_minimum_balance(step)
      scopes = DoubleEntry::AccountBalance.scopes_with_minimum_balance_for_account(
        Money.new(step['amount']), symbol(step['identifier'])
      )
      scopes.sort_by { |each| [each.nil? ? 0 : 1, each.to_s] }
    end

    # The nested steps run inside the block; an error of one leaves the
    # block, so the gem rolls the transaction back, and it is this step's
    # outcome. Their results are not recorded, and this step's value is
    # null: what lock_accounts returns is not behaviour to port.
    def lock_accounts(step)
      DoubleEntry.lock_accounts(*step['accounts'].map { |each| account(each) }) { run_steps(step['steps']) }
      nil
    end

    # A regular transaction around the nested steps, as locking_spec.rb:76-89
    # opens one: DoubleEntry::AccountBalance.transaction.
    def in_transaction(step)
      DoubleEntry::AccountBalance.transaction { run_steps(step['steps']) }
      nil
    end

    def run_steps(steps)
      steps.each { |each| run(each) }
    end

    # DoubleEntry.transfer(Money.new(amount), from:, to:, code:, detail:):
    # the amount is in cents of `currency`, else of Money.default_currency
    # (USD); code, detail and metadata are passed only when the step gives
    # them. Metadata keys are symbols, as transfer_spec.rb:84 writes them,
    # in sorted order: the order the file has them in, and the order the
    # gem creates the line_metadata rows in (transfer.rb:124).
    def transfer(step)
      options = { from: account(step['from']), to: account(step['to']) }
      options[:code] = symbol(step['code']) if step.key?('code')
      options[:detail] = Database.invoice(step['detail'].fetch('invoice')) if step.key?('detail')
      options[:metadata] = step['metadata'].sort.to_h { |key, value| [symbol(key), value] } if step.key?('metadata')
      amount = step.key?('currency') ? Money.new(step['amount'], step['currency']) : Money.new(step['amount'])
      DoubleEntry.transfer(amount, options)
    end

    # The attributes of Line.new, as locking_spec.rb:52-65 and
    # line_spec.rb:9-15 pass them: account instances, Money in cents, a
    # code as a symbol or nil. Only what the step gives.
    def line_attributes(step)
      attributes = {}
      %w[account partner_account].each { |name| attributes[name.to_sym] = account(step[name]) if step.key?(name) }
      %w[amount balance].each { |name| attributes[name.to_sym] = Money.new(step[name]) if step.key?(name) }
      attributes[:code] = symbol(step['code']) if step.key?('code')
      attributes
    end

    def line(step)
      DoubleEntry::Line.find(step['line'])
    end

    def line_attr(step)
      line = line(step)
      step.key?('attr') ? line.public_send(step['attr']) : line[step['column']]
    end

    # DoubleEntry.account(identifier, scope: ..., scope_identity: ...): an
    # option is passed only when the reference gives it.
    def account(reference)
      options = {}
      options[:scope] = scope(reference['op'], reference['scope']) if reference.key?('scope')
      options[:scope_identity] = reference['scope_identity'] if reference.key?('scope_identity')
      DoubleEntry.account(symbol(reference['identifier']), options)
    end

    # Always null: the value of an assignment is not behaviour to port.
    def set_config(step)
      configurable(step).configure { |config| config.public_send("#{step['key']}=", step['value']) }
      nil
    end

    def get_config(step)
      configurable(step).config.public_send(step['key'])
    end

    def configurable(step)
      Object.const_get(CONFIG_KEYS.fetch(step['key']))
    end

    def define_account(step)
      attributes = { identifier: symbol(step['identifier']) }
      scope = step.fetch('scope', 'none')
      attributes[:scope_identifier] = SCOPES.fetch(scope) unless scope == 'none'
      %w[positive_only negative_only currency].each do |name|
        attributes[name.to_sym] = step[name] if step.key?(name)
      end
      defined = nil
      DoubleEntry.configure do |config|
        config.define_accounts { |accounts| defined = accounts.define(attributes) }
      end
      defined
    end

    def define_transfer(step)
      defined = nil
      DoubleEntry.configure do |config|
        config.define_transfers do |transfers|
          defined = transfers.define(from: symbol(step['from']), to: symbol(step['to']), code: symbol(step['code']))
        end
      end
      defined
    end

    def transfer_key(step)
      [AccountStub.new(symbol(step['from'])), AccountStub.new(symbol(step['to'])), symbol(step['code'])]
    end

    # Identifiers and codes reach the gem as symbols, as an application's
    # configuration writes them.
    def symbol(value)
      value&.to_sym
    end
  end
end
