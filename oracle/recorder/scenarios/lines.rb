# frozen_string_literal: true

# spec/double_entry_spec.rb:166-229 and spec/double_entry/line_spec.rb. The
# transfer of double_entry_spec.rb:167-180 writes the credit line, id 1, on
# account a and the debit line, id 2, on account b: every database is fresh.
# The lines of line_spec.rb:9-15 are seeded with seed_line (create!, as
# save! of a new line) or line_save (save of a new line); the BTC account of
# spec/support/accounts.rb:10 is a BHD one.

lines_schema = { shape: 'generator', json_metadata: false }

# double_entry_spec.rb:167-180
transferred = lambda do
  define_account identifier: 'a'
  define_account identifier: 'b'
  define_transfer code: 'xfer', from: 'a', to: 'b'
  transfer amount: 1000, from: { identifier: 'a' }, to: { identifier: 'b' }, code: 'xfer'
end

credit = 1
debit = 2

scenario 'lines-amount-and-code',
         area: 'lines',
         source: %w[
           spec/double_entry_spec.rb:187
           spec/double_entry_spec.rb:192
           spec/double_entry/line_spec.rb:29
           spec/double_entry/line_spec.rb:63
         ],
         schema: lines_schema,
         description: 'A line has its amount as Money and its code as given; a line saved without a detail ' \
                      'keeps its code after a reload.' do
  instance_exec(&transferred)
  line_attr line: credit, attr: 'amount'
  line_attr line: debit, attr: 'amount'
  line_attr line: credit, attr: 'code'
  line_attr line: debit, attr: 'code'
  # line_spec.rb:17-19, 27-30
  define_account identifier: 'test', scope: 'user_id'
  seed_line account: { identifier: 'test', scope_identity: '17' },
            partner_account: { identifier: 'test', scope_identity: '72' },
            amount: 1000, balance: 0, code: 'the_code'
  line_attr line: 3, attr: 'code'
end

scenario 'lines-scope-columns',
         area: 'lines',
         source: 'spec/double_entry_spec.rb:197',
         schema: lines_schema,
         description: 'Assigning an unscoped account sets the account column and leaves the scope null, ' \
                      'for the partner account too.' do
  instance_exec(&transferred)
  line_attr line: credit, column: 'account'
  line_attr line: credit, column: 'scope'
  line_attr line: credit, column: 'partner_account'
  line_attr line: credit, column: 'partner_scope'
end

scenario 'lines-partner-and-pair',
         area: 'lines',
         source: %w[
           spec/double_entry_spec.rb:204
           spec/double_entry_spec.rb:215
           spec/double_entry_spec.rb:220
         ],
         schema: lines_schema,
         description: 'The credit and the debit are each other\'s partner, and the pair puts the credit first.' do
  instance_exec(&transferred)
  line_attr line: credit, attr: 'partner_account'
  line_attr line: debit, attr: 'account'
  line_partner line: credit
  line_partner line: debit
  line_pair line: credit
  line_pair line: debit
end

scenario 'lines-increase-decrease',
         area: 'lines',
         source: 'spec/double_entry_spec.rb:208',
         schema: lines_schema,
         description: 'The credit is a decrease and the debit an increase.' do
  instance_exec(&transferred)
  line_attr line: credit, attr: 'decrease?'
  line_attr line: debit, attr: 'increase?'
  line_attr line: credit, attr: 'increase?'
  line_attr line: debit, attr: 'decrease?'
end

scenario 'lines-account-instances',
         area: 'lines',
         source: %w[
           spec/double_entry_spec.rb:225
           spec/double_entry/line_spec.rb:46
           spec/double_entry/line_spec.rb:47
           spec/double_entry/line_spec.rb:52
           spec/double_entry/line_spec.rb:53
         ],
         schema: lines_schema,
         description: 'A line\'s account and partner account are account instances, ' \
                      'with the scope identities the line was saved with.' do
  instance_exec(&transferred)
  line_attr line: credit, attr: 'account'
  line_attr line: debit, attr: 'account'
  # line_spec.rb:44-54
  define_account identifier: 'test', scope: 'user_id'
  seed_line account: { identifier: 'test', scope_identity: '54' },
            partner_account: { identifier: 'test', scope_identity: '91' },
            amount: 1000, balance: 0, code: 'test_code'
  line_attr line: 3, attr: 'account'
  line_attr line: 3, attr: 'partner_account'
end

scenario 'lines-currency',
         area: 'lines',
         source: 'spec/double_entry/line_spec.rb:59',
         schema: lines_schema,
         description: 'A line takes its currency from its account.' do
  define_account identifier: 'bhd_test', scope: 'user_id', currency: 'BHD'
  seed_line account: { identifier: 'bhd_test', scope_identity: '17' },
            partner_account: { identifier: 'bhd_test', scope_identity: '72' },
            amount: 1000, balance: 0, code: 'test_code'
  line_attr line: 1, attr: 'amount'
  line_attr line: 1, attr: 'currency'
end

scenario 'lines-positive-only-violation',
         area: 'lines',
         source: 'spec/double_entry/line_spec.rb:72',
         schema: lines_schema,
         description: 'Saving a negative balance on a positive-only account raises AccountWouldBeSentNegative.' do
  define_account identifier: 'a_positive_only_acc', positive_only: true
  line_save account: { identifier: 'a_positive_only_acc' }, balance: -1
end

scenario 'lines-negative-only-violation',
         area: 'lines',
         source: 'spec/double_entry/line_spec.rb:82',
         schema: lines_schema,
         description: 'Saving a positive balance on a negative-only account raises AccountWouldBeSentPositiveError.' do
  define_account identifier: 'a_negative_only_acc', negative_only: true
  line_save account: { identifier: 'a_negative_only_acc' }, balance: 1
end

scenario 'lines-null-code-rejected',
         area: 'lines',
         source: 'spec/double_entry/line_spec.rb:41',
         schema: lines_schema,
         description: 'A line without a code is rejected by the NOT NULL constraint of the code column.' do
  define_account identifier: 'test', scope: 'user_id'
  seed_line account: { identifier: 'test', scope_identity: '17' },
            partner_account: { identifier: 'test', scope_identity: '72' },
            amount: 1000, balance: 0, code: nil
end

scenario 'lines-credits-debits',
         area: 'lines',
         source: 'spec/double_entry_spec.rb:208',
         schema: lines_schema,
         description: 'Line.credits lists the lines with a positive amount and Line.debits those with ' \
                      'a negative one, the other way round from the transfer\'s names (U-07).' do
  instance_exec(&transferred)
  lines_scope scope: 'credits'
  lines_scope scope: 'debits'
end
