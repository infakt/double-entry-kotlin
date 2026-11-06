# frozen_string_literal: true

# spec/double_entry/transfer_spec.rb:50-199: a transfer's metadata, kept in
# the double_entry_line_metadata table (json_metadata false, the default) or
# in the lines' json metadata column (json_metadata true, set with
# set_config). The scenarios run on the spec schema, which has both
# (spec/support/schema.rb), as the gem's suite does; the rows are in the
# table effects. The spec's user is User 1, and its test and savings
# accounts and bonus rule are those of spec/support/accounts.rb:7, 9 and 19.
# The transfer's credit is line 1 (test) and its debit line 2 (savings).
#
# line_metadata_of reads Line#metadata. The has_many of line.rb:59 is never
# defined (U-06), so that is the json column on the spec schema, and
# NoMethodError on the generator schema without json_metadata, which has no
# such column (metadata-table-generator-schema, X-04).

spec_schema = { shape: 'spec' }
spec = 'spec/double_entry/transfer_spec.rb'

user = { user: 1 }
test = { identifier: 'test', scope: user }
savings = { identifier: 'savings', scope: user }

transfer_accounts = lambda do
  define_account identifier: 'savings', scope: 'user_id', positive_only: true
  define_account identifier: 'test', scope: 'user_id'
  define_transfer from: 'test', to: 'savings', code: 'bonus'
end

pairs = { country: 'AU', tax: 'GST' }
array_values = { tax: %w[GST VAT] }

# metadata-table: json_metadata false.

scenario 'metadata-table-none',
         area: 'metadata-table',
         source: ["#{spec}:70", "#{spec}:74"],
         schema: spec_schema,
         description: 'Without json_metadata, a transfer without metadata creates no line_metadata rows ' \
                      'and leaves the lines\' metadata column empty.' do
  instance_exec(&transfer_accounts)
  transfer amount: 1000, from: test, to: savings, code: 'bonus'
  line_metadata_count
  line_metadata_of line: 1
  line_metadata_of line: 2
end

scenario 'metadata-table-pairs',
         area: 'metadata-table',
         source: ["#{spec}:111", "#{spec}:115", "#{spec}:121", "#{spec}:129", "#{spec}:135", "#{spec}:142"],
         schema: spec_schema,
         description: 'Without json_metadata, a transfer with two key/value pairs creates one line_metadata row ' \
                      'per pair and line, and leaves the lines\' metadata column empty.' do
  instance_exec(&transfer_accounts)
  transfer amount: 1000, from: test, to: savings, code: 'bonus', metadata: pairs
  line_metadata_count
  line_metadata_of line: 1
  line_metadata_of line: 2
end

scenario 'metadata-table-array-values',
         area: 'metadata-table',
         source: ["#{spec}:174", "#{spec}:178", "#{spec}:184", "#{spec}:192"],
         schema: spec_schema,
         description: 'Without json_metadata, a key with an array of values creates one line_metadata row ' \
                      'per value and line, and leaves the lines\' metadata column empty.' do
  instance_exec(&transfer_accounts)
  transfer amount: 1000, from: test, to: savings, code: 'bonus', metadata: array_values
  line_metadata_count
  line_metadata_of line: 1
  line_metadata_of line: 2
end

scenario 'metadata-table-generator-schema',
         area: 'metadata-table',
         source: ["#{spec}:111", "#{spec}:142"],
         schema: { shape: 'generator', json_metadata: false },
         description: 'On the generator\'s tables without json_metadata, the metadata goes to line_metadata, ' \
                      'and a line has no metadata reader: Line#metadata raises NoMethodError.' do
  instance_exec(&transfer_accounts)
  transfer amount: 1000, from: test, to: savings, code: 'bonus', metadata: pairs
  line_metadata_count
  line_metadata_of line: 1
end

# metadata-json: json_metadata true.

scenario 'metadata-json-none',
         area: 'metadata-json',
         source: ["#{spec}:57", "#{spec}:61"],
         schema: spec_schema,
         description: 'With json_metadata, a transfer without metadata creates no line_metadata rows ' \
                      'and leaves the lines\' metadata column empty.' do
  set_config key: 'json_metadata', value: true
  instance_exec(&transfer_accounts)
  transfer amount: 1000, from: test, to: savings, code: 'bonus'
  line_metadata_count
  line_metadata_of line: 1
  line_metadata_of line: 2
end

scenario 'metadata-json-pairs',
         area: 'metadata-json',
         source: ["#{spec}:93", "#{spec}:97", "#{spec}:102"],
         schema: spec_schema,
         description: 'With json_metadata, a transfer with two key/value pairs stores them in both lines\' ' \
                      'metadata column and creates no line_metadata rows.' do
  set_config key: 'json_metadata', value: true
  instance_exec(&transfer_accounts)
  transfer amount: 1000, from: test, to: savings, code: 'bonus', metadata: pairs
  line_metadata_count
  line_metadata_of line: 1
  line_metadata_of line: 2
end

scenario 'metadata-json-array-values',
         area: 'metadata-json',
         source: ["#{spec}:161", "#{spec}:165"],
         schema: spec_schema,
         description: 'With json_metadata, a key with an array of values stores the array in both lines\' ' \
                      'metadata column and creates no line_metadata rows.' do
  set_config key: 'json_metadata', value: true
  instance_exec(&transfer_accounts)
  transfer amount: 1000, from: test, to: savings, code: 'bonus', metadata: array_values
  line_metadata_count
  line_metadata_of line: 1
  line_metadata_of line: 2
end
