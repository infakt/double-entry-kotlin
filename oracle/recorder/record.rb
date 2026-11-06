# frozen_string_literal: true

# Records the gem's behaviour into oracle/scenarios/<id>.json and rewrites
# oracle/index.json. Run it through oracle/record; the contract of the files
# it writes is oracle/README.md.

require 'json'
require 'time'
require 'logger'

require 'double_entry'
# The Railtie hook (lib/double_entry.rb:25-33) that requires these never
# fires without a Rails application. Both are required, as the gem's
# spec/spec_support.rb does, whatever json_metadata says.
require 'double_entry/line_metadata'
require 'double_entry/line'
require 'timecop'

require_relative 'values'
require_relative 'database'
require_relative 'injections'
require_relative 'ops'
require_relative 'dsl'

module Oracle
  ORACLE = File.expand_path('..', __dir__)
  SCENARIOS = File.join(ORACLE, 'scenarios')
  DEFINITIONS = File.join(__dir__, 'scenarios')

  # Process-wide setting, the same for every scenario, as spec/support/money.rb
  # sets it. Money.default_currency is set per scenario, by the reset.
  Money.locale_backend = :i18n

  module_function

  def main
    definitions = Definitions.new(areas)
    Dir[File.join(DEFINITIONS, '*.rb')].sort.each { |path| definitions.load(path) }
    ids = skeleton_ids
    check_pairs(ids, definitions.scenarios.keys)

    Database.directory do |directory|
      ids.each do |id|
        write(File.join(SCENARIOS, "#{id}.json"), record(definitions.scenarios.fetch(id), directory))
      end
    end
    write(File.join(ORACLE, 'index.json'), index)
  end

  # The areas of oracle/enabled.json, {"areas": {"<area>": "on"|"off"}}.
  def areas
    path = File.join(ORACLE, 'enabled.json')
    areas = JSON.parse(File.read(path))['areas']
    raise RecorderError, "#{path}: no \"areas\" object" unless areas.is_a?(Hash) && !areas.empty?

    areas.keys
  end

  # The ids of oracle/scenarios/*.json: a skeleton {"id": "<id>"} or a file
  # an earlier run recorded.
  def skeleton_ids
    Dir[File.join(SCENARIOS, '*.json')].sort.map do |path|
      id = JSON.parse(File.read(path))['id']
      expected = File.basename(path, '.json')
      raise RecorderError, "#{path}: the id is #{id.inspect}, not #{expected.inspect}" unless id == expected

      id
    end
  end

  def check_pairs(skeletons, defined)
    without_definition = skeletons - defined
    without_skeleton = defined - skeletons
    unless without_definition.empty?
      raise RecorderError, "skeleton without a definition: #{without_definition.sort.join(', ')}"
    end
    return if without_skeleton.empty?

    raise RecorderError, "definition without a skeleton in oracle/scenarios/: #{without_skeleton.sort.join(', ')}"
  end

  # The outcome is the last step's. The earlier steps' results go to
  # effects.steps, in order; an earlier step may not raise, since a raising
  # step ends the scenario and is its outcome. Each scenario has a fresh
  # database, created before the clock is frozen. A scenario declared with
  # events: true collects the gem's notifications while its steps run.
  def record(scenario, directory)
    reset
    Database.prepare(directory, scenario)
    outcomes = nil
    events = Events.collect do
      outcomes = Timecop.freeze(Time.iso8601(scenario.clock)) do
        scenario.steps.map { |step| Ops.outcome(step) }
      end
    end
    check_outcomes(scenario, outcomes)
    *earlier, outcome = outcomes
    {
      'area' => scenario.area,
      'description' => scenario.description,
      'effects' => effects(scenario, earlier.map { |each| each.fetch('result') }, events),
      'id' => scenario.id,
      'input' => input(scenario),
      'outcome' => outcome,
      'source' => scenario.source,
    }
  ensure
    Timecop.return
  end

  # Only the last step may raise, and every error inject_error arranged must
  # have been raised: one left over means the definition does not drive the
  # path it was written for.
  def check_outcomes(scenario, outcomes)
    outcomes[0...-1].each_with_index do |each, index|
      next unless each.key?('error')

      raise RecorderError, "scenario #{scenario.id}: step #{index + 1} of #{outcomes.size} raises " \
                           "#{each['error']['class']}; a raising step must be the last"
    end
    unused = Injections.unused
    return if unused.empty?

    raise RecorderError, "scenario #{scenario.id}: inject_error left unraised: " \
                         "#{unused.map { |target, count| "#{count} on #{target}" }.join(', ')}"
  end

  def input(scenario)
    input = { 'clock' => scenario.clock, 'steps' => scenario.steps }
    input['schema'] = scenario.schema if scenario.schema
    input
  end

  # {"steps": [the earlier steps' results]}. A scenario with a schema adds
  # the rows of the gem's four tables under their table names; one without
  # (the config-only areas, accounts) still runs on the default schema, but
  # writes no table. A scenario that used the users table adds its rows as
  # `users`, and one declared with events: true adds `events`.
  def effects(scenario, steps, events)
    effects = { 'steps' => steps }
    effects.merge!(Database.effects) if scenario.schema
    effects['users'] = Database.users_rows if Database.users_used?
    effects['events'] = events if scenario.events
    effects
  end

  # The gem's state as a fresh process has it. Dropping the memoised
  # configurations lets the gem's own initializers set the defaults
  # (json_metadata false, running_inside_transactional_fixtures false).
  def reset
    DoubleEntry.instance_variable_set(:@configuration, nil)
    DoubleEntry::Locking.instance_variable_set(:@configuration, nil)
    DoubleEntry::Account.accounts = DoubleEntry::Account::Set.new
    DoubleEntry::Transfer.transfers = DoubleEntry::Transfer::Set.new
    DoubleEntry::Account.scope_identifier_max_length = nil
    DoubleEntry::Account.account_identifier_max_length = nil
    DoubleEntry::Transfer.code_max_length = nil
    Injections.reset
    # Before any account is defined: an account takes the default currency
    # when it is defined (account.rb:148). See U-02 in oracle/unknowns.md.
    Money.default_currency = Money::Currency.new('USD')
  end

  # {"scenarios": [{id, area, description, outcome: returns|raises}]}, one
  # entry per file of oracle/scenarios/, sorted by id.
  def index
    entries = Dir[File.join(SCENARIOS, '*.json')].map do |path|
      scenario = JSON.parse(File.read(path))
      {
        'area' => scenario.fetch('area'),
        'description' => scenario.fetch('description'),
        'id' => scenario.fetch('id'),
        'outcome' => scenario.fetch('outcome').key?('error') ? 'raises' : 'returns',
      }
    end
    { 'scenarios' => entries.sort_by { |entry| entry['id'] } }
  end

  def write(path, object)
    text = Values.json(object)
    File.write(path, text) unless File.exist?(path) && File.read(path) == text
  end
end

begin
  Oracle.main
rescue Oracle::RecorderError => e
  warn "oracle/record: #{e.message}"
  exit 1
end
