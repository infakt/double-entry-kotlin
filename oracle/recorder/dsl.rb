# frozen_string_literal: true

module Oracle
  Scenario = Struct.new(:id, :area, :description, :source, :schema, :clock, :events, :steps, keyword_init: true)

  # Loads the definitions in oracle/recorder/scenarios/*.rb:
  #
  #   scenario 'transfer-rules-find', area: 'transfer-rules',
  #            source: 'spec/double_entry/transfer_spec.rb:223',
  #            description: '...' do
  #     define_transfer from: 'from', to: 'to', code: 'transfer_code'
  #   end
  class Definitions
    ID = /\A[a-z0-9]+(?:-[a-z0-9]+)*\z/
    SOURCE = %r{\Aspec/[\w/.-]+_spec\.rb:\d+\z}
    DEFAULT_CLOCK = '2024-01-01T00:00:00Z'

    attr_reader :scenarios

    def initialize(areas)
      @areas = areas
      @scenarios = {}
    end

    def load(path)
      instance_eval(File.read(path), path)
    end

    # events: true writes the gem's notifications as effects.events.
    def scenario(id, area:, source:, description:, schema: nil, clock: DEFAULT_CLOCK, events: false, &block)
      sources = Array(source)
      raise RecorderError, "scenario id #{id.inspect} is not kebab-case" unless id.is_a?(String) && ID.match?(id)
      raise RecorderError, "scenario #{id} is defined twice" if @scenarios.key?(id)
      raise RecorderError, "scenario #{id}: unknown area #{area}" unless @areas.include?(area)
      raise RecorderError, "scenario #{id} does not start with its area #{area}" unless id.start_with?("#{area}-")
      raise RecorderError, "scenario #{id}: no source" if sources.empty?
      sources.each do |each|
        raise RecorderError, "scenario #{id}: source #{each} is not spec/...rb:line" unless SOURCE.match?(each)
      end
      raise RecorderError, "scenario #{id}: no description" if description.to_s.strip.empty?
      raise RecorderError, "scenario #{id}: no steps" unless block
      raise RecorderError, "scenario #{id}: events is true or false" unless [true, false].include?(events)

      Time.iso8601(clock)
      schema = Database.check(Values.value(schema)) if schema
      steps = Steps.new
      steps.instance_eval(&block)
      @scenarios[id] = Scenario.new(
        id: id, area: area, description: description, source: sources,
        schema: schema, clock: clock, events: events, steps: steps.to_a
      )
    rescue ArgumentError => e
      raise RecorderError, "scenario #{id}: #{e.message}"
    end
  end

  # The step methods of a definition: one per op of Ops::ARGUMENTS. The ops
  # that nest steps (lock_accounts, in_transaction) take them as a block:
  #
  #   lock_accounts accounts: [{ identifier: 'account_a', scope: '1' }] do
  #     transfer amount: 1000, from: ..., to: ..., code: 'test'
  #   end
  class Steps
    def initialize
      @steps = []
    end

    def to_a
      @steps
    end

    Ops::ARGUMENTS.each_key do |op|
      define_method(op) do |**arguments, &block|
        if block
          nested = Steps.new
          nested.instance_eval(&block)
          arguments[:steps] = nested.to_a
        end
        step = { 'op' => op }.merge(Values.value(arguments))
        @steps << Ops.check(step)
      end
    end
  end
end
