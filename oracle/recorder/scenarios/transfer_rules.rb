# frozen_string_literal: true

# spec/double_entry/transfer_spec.rb:4-23 and 202-266, and the duplicate check
# of spec/double_entry_spec.rb:29. The find ops take account stand-ins that
# carry only an identifier, as transfer_spec.rb:219-220.

code47 = 'xxxxxxxxxxxxxxxx 47 characters xxxxxxxxxxxxxxxx'
code48 = 'xxxxxxxxxxxxxxxx 48 characters xxxxxxxxxxxxxxxxx'

scenario 'transfer-rules-find',
         area: 'transfer-rules',
         source: %w[
           spec/double_entry/transfer_spec.rb:223
           spec/double_entry/transfer_spec.rb:246
           spec/double_entry/configuration_spec.rb:4
           spec/double_entry/configuration_spec.rb:44
         ],
         description: 'Two rules between the same accounts with different codes are both found, ' \
                      'by find and by find!.' do
  define_transfer from: 'from', to: 'to', code: 'transfer_code'
  define_transfer from: 'from', to: 'to', code: 'another_transfer_code'
  find_transfer from: 'from', to: 'to', code: 'transfer_code'
  find_transfer from: 'from', to: 'to', code: 'another_transfer_code'
  find_transfer! from: 'from', to: 'to', code: 'transfer_code'
  find_transfer! from: 'from', to: 'to', code: 'another_transfer_code'
end

scenario 'transfer-rules-find-undefined',
         area: 'transfer-rules',
         source: 'spec/double_entry/transfer_spec.rb:238',
         description: 'find returns nothing for a rule that was not defined (the reverse direction).' do
  define_transfer from: 'from', to: 'to', code: 'transfer_code'
  define_transfer from: 'from', to: 'to', code: 'another_transfer_code'
  find_transfer from: 'to', to: 'from', code: 'transfer_code'
end

scenario 'transfer-rules-find-bang-undefined',
         area: 'transfer-rules',
         source: 'spec/double_entry/transfer_spec.rb:261',
         description: 'find! raises TransferNotAllowed for a rule that was not defined (the reverse direction).' do
  define_transfer from: 'from', to: 'to', code: 'transfer_code'
  define_transfer from: 'from', to: 'to', code: 'another_transfer_code'
  find_transfer! from: 'to', to: 'from', code: 'transfer_code'
end

scenario 'transfer-rules-define-duplicate',
         area: 'transfer-rules',
         source: 'spec/double_entry_spec.rb:29',
         description: 'Defining the same from, to and code twice raises DuplicateTransfer.' do
  define_transfer from: 'savings', to: 'cash', code: 'xfer'
  define_transfer from: 'savings', to: 'cash', code: 'xfer'
end

scenario 'transfer-rules-code-at-max-length',
         area: 'transfer-rules',
         source: 'spec/double_entry/transfer_spec.rb:11',
         description: 'With code_max_length 47, a code of 47 characters is accepted.' do
  set_config key: 'code_max_length', value: 47
  define_transfer from: 'from', to: 'to', code: code47
end

scenario 'transfer-rules-code-over-max-length',
         area: 'transfer-rules',
         source: 'spec/double_entry/transfer_spec.rb:18',
         description: 'With code_max_length 47, a code of 48 characters raises TransferCodeTooLongError.' do
  set_config key: 'code_max_length', value: 47
  define_transfer from: 'from', to: 'to', code: code48
end
