# frozen_string_literal: true

require 'erb'
require 'tmpdir'

module Oracle
  # The SQLite database of a scenario: a fresh file per scenario, the schema
  # of input.schema, the rows of the gem's four tables for effects, and the
  # shape of the tables for describe_schema. See "Database" in
  # oracle/README.md.
  module Database
    REFERENCE = File.expand_path('../../reference/double_entry', __dir__)
    MIGRATION = File.join(REFERENCE, 'lib/generators/double_entry/install/templates/migration.rb')
    SPEC_SCHEMA = File.join(REFERENCE, 'spec/support/schema.rb')
    # What the install generator renders into the template for Rails 8.0.
    MIGRATION_VERSION = '[8.0]'

    # Without input.schema a scenario runs on this one.
    DEFAULT_SCHEMA = { 'shape' => 'generator', 'json_metadata' => false }.freeze

    # The gem's four tables, by the model that maps each one.
    TABLES = {
      'double_entry_account_balances' => 'DoubleEntry::AccountBalance',
      'double_entry_line_checks' => 'DoubleEntry::Validation::LineCheck',
      'double_entry_line_metadata' => 'DoubleEntry::LineMetadata',
      'double_entry_lines' => 'DoubleEntry::Line',
    }.freeze

    # Active Record's abstract column type => the type a scenario writes.
    TYPES = {
      string: 'string',
      integer: 'integer',
      bigint: 'integer',
      boolean: 'boolean',
      text: 'text',
      json: 'json',
      jsonb: 'json',
      datetime: 'datetime',
      timestamp: 'datetime',
    }.freeze

    module_function

    # Checks a definition's schema: when it loads, before anything is
    # recorded.
    def check(schema)
      raise RecorderError, "schema is an object, not #{schema.inspect}" unless schema.is_a?(Hash)

      case schema['shape']
      when 'generator'
        unless schema.keys.sort == %w[json_metadata shape] && [true, false].include?(schema['json_metadata'])
          raise RecorderError, "a generator schema is {shape, json_metadata: true|false}, not #{schema.inspect}"
        end
      when 'spec'
        raise RecorderError, "a spec schema is {shape} only, not #{schema.inspect}" unless schema.keys == ['shape']
      else
        raise RecorderError, "schema shape is generator or spec, not #{schema['shape'].inspect}"
      end
      schema
    end

    # Runs the block with a directory for the scenarios' databases, under
    # Dir.tmpdir (TMPDIR), never under reference/, removed afterwards.
    def directory
      Dir.mktmpdir('oracle-record-') do |directory|
        yield directory
      ensure
        ActiveRecord::Base.remove_connection
      end
    end

    # A fresh database for the scenario, with its schema.
    def prepare(directory, scenario)
      path = File.join(directory, "#{scenario.id}.sqlite3")
      raise RecorderError, "scenario #{scenario.id}: #{path} exists" if File.exist?(path)

      connect(path)
      create(scenario.schema || DEFAULT_SCHEMA)
      @users_used = false
    end

    # After each switch the models forget the columns of the last database.
    def connect(path)
      ActiveRecord::Base.establish_connection(adapter: 'sqlite3', database: path)
      models.each(&:reset_column_information)
    end

    def models
      TABLES.values.map { |name| Object.const_get(name) }
    end

    def create(schema)
      case schema.fetch('shape')
      when 'generator' then migrate(schema.fetch('json_metadata'))
      # Kernel#load only reads the file.
      when 'spec' then Kernel.load(SPEC_SCHEMA)
      end
    end

    # The generator's migration, rendered as the install generator renders
    # it, then `up`. It is evaluated inside a fresh module, so each scenario
    # has its own CreateDoubleEntryTables.
    def migrate(json_metadata)
      template = ERB.new(File.read(MIGRATION), trim_mode: '-')
      source = template.result_with_hash(migration_version: MIGRATION_VERSION, json_metadata: json_metadata)
      namespace = Module.new
      namespace.module_eval(source, MIGRATION)
      ActiveRecord::Migration.verbose = false
      namespace.const_get(:CreateDoubleEntryTables).migrate(:up)
    end

    def connection
      ActiveRecord::Base.connection
    end

    # The persisted Invoice a transfer's detail points at, in a table the
    # recorder adds on first use. It is none of the gem's four tables, so
    # describe_schema and the table effects leave it out.
    def invoice(id)
      connection.create_table(::Invoice.table_name) unless connection.table_exists?(::Invoice.table_name)
      ::Invoice.reset_column_information
      ::Invoice.find_or_create_by!(id: id)
    end

    # The table the locking extensions run on, as spec/support/schema.rb:58-63
    # has it (a username, unique, and timestamps), added by the recorder on
    # first use; the spec shape already has it. It is none of the gem's four
    # tables, so describe_schema and the four tables' effects leave it out;
    # its rows are effects.users of a scenario that used it.
    def users
      unless connection.table_exists?(::UserRecord.table_name)
        connection.create_table(::UserRecord.table_name) do |table|
          table.string :username, null: false
          table.timestamps null: false
        end
        connection.add_index(::UserRecord.table_name, :username, unique: true, name: 'index_users_on_username')
      end
      ::UserRecord.reset_column_information
      @users_used = true
      ::UserRecord
    end

    def users_used?
      @users_used == true
    end

    # The users rows by id, every column, as the four tables' rows are written.
    def users_rows
      table = connection.quote_table_name(::UserRecord.table_name)
      rows = connection.select_all("SELECT * FROM #{table} ORDER BY id")
      rows.to_a.map { |row| row.to_h { |column, raw| [column, cell(::UserRecord, column, raw)] } }
    end

    # The rows of each of the four tables, by id, as plain values; [] for a
    # table the schema lacks.
    def effects
      TABLES.to_h do |table, model_name|
        next [table, []] unless connection.table_exists?(table)

        model = Object.const_get(model_name)
        unless model.table_name == table
          raise RecorderError, "#{model_name} maps #{model.table_name}, not #{table}"
        end

        rows = connection.select_all("SELECT * FROM #{connection.quote_table_name(table)} ORDER BY id")
        [table, rows.to_a.map { |row| row.to_h { |column, raw| [column, cell(model, column, raw)] } }]
      end
    end

    # A cell as its model reads it, without the model's own readers (Line's
    # amount is the integer column, not Money). Times are ISO 8601 UTC to the
    # second. A line check's log is masked as its value is (Values.masked_log).
    def cell(model, column, raw)
      value = model.type_for_attribute(column).deserialize(raw)
      value = Values.masked_log(value) if model == DoubleEntry::Validation::LineCheck && column == 'log'
      case value
      when Time, ActiveSupport::TimeWithZone then value.utc.iso8601
      else Values.value(value)
      end
    end

    # {"<table>": {"columns": [{name, type, null}], "indexes": [{name,
    # columns, unique}]}} for each of the four tables the schema has.
    # Columns are in table order, indexes by name.
    def describe
      TABLES.keys.select { |table| connection.table_exists?(table) }.to_h do |table|
        [table, { 'columns' => columns(table), 'indexes' => indexes(table) }]
      end
    end

    def columns(table)
      connection.columns(table).map do |column|
        type = TYPES.fetch(column.type) do
          raise RecorderError, "#{table}.#{column.name}: no normalized type for #{column.type.inspect}"
        end
        { 'name' => column.name, 'null' => column.null, 'type' => type }
      end
    end

    def indexes(table)
      connection.indexes(table).sort_by(&:name).map do |index|
        { 'columns' => Array(index.columns), 'name' => index.name, 'unique' => index.unique }
      end
    end
  end
end
