# frozen_string_literal: true

# The model of the users table the recorder adds on first use
# (Database.users), as locking_extensions_spec.rb drives the extensions on
# the spec suite's User. ops.rb already names the scope Struct User, so this
# one is UserRecord.
unless defined?(UserRecord)
  class UserRecord < ActiveRecord::Base
    self.table_name = 'users'
  end
end

module Oracle
  # The errors inject_error arranges: per target, the exceptions still to be
  # raised, in order. Reset before every scenario; one left unused at the
  # end stops the recorder. See "Locking extensions" in oracle/README.md.
  module Injections
    TARGETS = %w[with_restart_on_deadlock create!].freeze
    # The classes inject_error may raise; the first is the default.
    ERRORS = {
      'ActiveRecord::StatementInvalid' => ActiveRecord::StatementInvalid,
      'ActiveRecord::RestartTransaction' => ActiveRecord::RestartTransaction,
    }.freeze

    @pending = Hash.new { |hash, target| hash[target] = [] }

    module_function

    def reset
      @pending.clear
    end

    # `times` exceptions of `error` with `message`, each marked as injected,
    # so its message is recorded exactly (see Errors in oracle/README.md).
    def add(target, error, message, times)
      times.times { @pending[target] << ERRORS.fetch(error).new(message).extend(Injected) }
    end

    # The next exception for the target, or nil when none is left.
    def take(target)
      @pending[target].shift
    end

    def unused
      @pending.reject { |_target, errors| errors.empty? }.transform_values(&:size)
    end

    # Replaces the body that with_restart_on_deadlock wraps, not the method:
    # the gem's own rescue (locking_extensions.rb:19-26) still decides
    # whether the error is a deadlock, publishes, and raises
    # RestartTransaction or re-raises. locking_spec.rb:155 and 168 stub the
    # method itself, which skips that rescue.
    module RestartOnDeadlock
      def with_restart_on_deadlock(&block)
        super() do
          error = Injections.take('with_restart_on_deadlock')
          raise error if error

          block.call
        end
      end
    end

    # Raises before UserRecord.create! runs, as locking_extensions_spec.rb:10
    # and 72 make User.create! raise; once the errors are used up, the real
    # create! inserts the row.
    module Create
      def create!(*args, &block)
        error = Injections.take('create!')
        raise error if error

        super
      end
    end

    DoubleEntry::AccountBalance.singleton_class.prepend(RestartOnDeadlock)
    ::UserRecord.singleton_class.prepend(Create)
  end

  # The gem's notifications, ActiveSupport::Notifications *.double_entry,
  # in the order they are published, for a scenario declared with
  # events: true. Each is {name, exception_class, message}; the message
  # follows the engine-message rule (Values.message).
  module Events
    PATTERN = /\.double_entry\z/

    module_function

    # Runs the block with a subscriber, and returns the events it saw.
    def collect
      events = []
      subscriber = ActiveSupport::Notifications.subscribe(PATTERN) do |name, *args|
        events << event(name, args.last)
      end
      yield
      events
    ensure
      ActiveSupport::Notifications.unsubscribe(subscriber) if subscriber
    end

    # The gem publishes every event with {exception: e}
    # (locking_extensions.rb:21, 49, 66).
    def event(name, payload)
      exception = payload.is_a?(Hash) ? payload[:exception] : nil
      raise RecorderError, "event #{name}: no exception in #{payload.inspect}" unless exception.is_a?(Exception)

      { 'exception_class' => exception.class.name, 'message' => Values.message(exception), 'name' => name }
    end
  end
end
