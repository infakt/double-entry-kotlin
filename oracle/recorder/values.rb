# frozen_string_literal: true

module Oracle
  # A mistake in the recorder or in a definition. It is never recorded as an
  # outcome: it stops the recorder.
  class RecorderError < StandardError; end

  # Marks an exception a scenario raised itself (raise, inject_error), so its
  # message is recorded exactly even when its class is an engine error.
  module Injected; end

  # Turns what the gem returns or raises into the JSON values of a scenario
  # file. See "Values" and "Errors" in oracle/README.md.
  module Values
    ENGINE_MESSAGE = '<engine>'

    # The 'Error on line' entry of a line check's log (line_check.rb:97-106):
    # three lines of its own, then the inspect of the previous line and of
    # the line (line_check.rb:102-103), two lines of two spaces each. Active
    # Record's inspect is not behaviour to port, so those two lines become one.
    LINE_ERROR = /^(\*+\nError on line #\d+: [^\n]*\n\*+\n)  [^\n]*\n  [^\n]*\n/
    MASKED = '  <masked>'

    module_function

    def value(object)
      case object
      when nil, true, false, Integer, String then object
      when Symbol then object.to_s
      when Array then object.map { |each| value(each) }
      when Hash then object.to_h { |key, each| [key.to_s, value(each)] }
      when Money then money(object)
      when Money::Currency then object.iso_code
      when DoubleEntry::Account::Instance then account_instance(object)
      when DoubleEntry::Account then account(object)
      when DoubleEntry::Transfer then transfer(object)
      when DoubleEntry::Line then line(object)
      when DoubleEntry::AccountBalance then account_balance(object)
      when DoubleEntry::Validation::LineCheck then line_check(object)
      else
        raise RecorderError, "the recorder cannot write a #{object.class}: #{object.inspect}"
      end
    end

    def money(money)
      { 'cents' => Integer(money.cents), 'currency' => money.currency.iso_code }
    end

    def account_instance(instance)
      {
        'currency' => value(instance.currency),
        'identifier' => value(instance.identifier),
        'scope_identity' => instance.scope_identity,
      }
    end

    # An account definition, as Account::Set#define returns it.
    def account(account)
      {
        'currency' => value(account.currency),
        'identifier' => value(account.identifier),
        'negative_only' => value(account.negative_only),
        'positive_only' => value(account.positive_only),
        'scoped' => account.scoped?,
      }
    end

    # A transfer rule, as Transfer::Set#define and #find return it.
    def transfer(transfer)
      {
        'code' => value(transfer.code),
        'from' => value(transfer.from),
        'to' => value(transfer.to),
      }
    end

    # A line, as DoubleEntry.transfer, Line#partner, #pair and the scopes
    # return it: its columns as stored, but amount and balance through Line's
    # readers, Money in the account's currency. No timestamps: the table
    # effects have them.
    def line(line)
      {
        'account' => line[:account],
        'amount' => value(line.amount),
        'balance' => value(line.balance),
        'code' => line[:code],
        'detail_id' => line[:detail_id],
        'detail_type' => line[:detail_type],
        'id' => line.id,
        'partner_account' => line[:partner_account],
        'partner_id' => line[:partner_id],
        'partner_scope' => line[:partner_scope],
        'scope' => line[:scope],
      }
    end

    # An account balance record: its columns as stored, but balance through
    # AccountBalance's reader, Money in the account's currency.
    def account_balance(record)
      {
        'account' => record[:account],
        'balance' => value(record.balance),
        'id' => record.id,
        'scope' => record[:scope],
      }
    end

    # A line check, as LineCheck.perform! and create! return it: its columns
    # as stored, the log masked (see masked_log). No timestamps: the table
    # effects have them.
    def line_check(check)
      {
        'errors_found' => check[:errors_found],
        'id' => check.id,
        'last_line_id' => check[:last_line_id],
        'log' => masked_log(check[:log]),
      }
    end

    # A line check's log with the inspect lines of each 'Error on line' entry
    # (line_check.rb:102-103) replaced by MASKED. An entry the pattern does
    # not match stops the recorder, so no inspect text is ever recorded.
    def masked_log(log)
      return log unless log.is_a?(String)

      masked = log.gsub(LINE_ERROR) { "#{Regexp.last_match(1)}#{MASKED}\n" }
      if masked.scan('Error on line #').size != masked.scan("\n#{MASKED}\n").size
        raise RecorderError, "a line check log whose 'Error on line' entry cannot be masked: #{log.inspect}"
      end

      masked
    end

    # The error of an outcome ({"error" => this}), and the same shape for an
    # exception inside recorded events.
    def error(exception)
      { 'class' => exception.class.name, 'message' => message(exception) }
    end

    def message(exception)
      engine_error?(exception) ? ENGINE_MESSAGE : exception.message
    end

    # Raised by the database engine or its driver, not by the library: the
    # wording is SQLite's, never PostgreSQL's, so only the class is compared.
    # NotNullViolation and RecordNotUnique are StatementInvalid subclasses.
    def engine_error?(exception)
      return false if exception.is_a?(Injected)

      exception.is_a?(ActiveRecord::StatementInvalid) || exception.class.name.to_s.start_with?('SQLite3::')
    end

    # Sorted keys at every level, so a re-run writes the same bytes.
    def sorted(object)
      case object
      when Hash then object.keys.sort.to_h { |key| [key, sorted(object[key])] }
      when Array then object.map { |each| sorted(each) }
      else object
      end
    end

    def json(object)
      "#{JSON.pretty_generate(sorted(object))}\n"
    end
  end
end
