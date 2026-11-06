# frozen_string_literal: true

# spec/double_entry/configuration_spec.rb

scenario 'configuration-defaults',
         area: 'configuration',
         source: %w[
           spec/double_entry/configuration_spec.rb:8
           spec/double_entry/configuration_spec.rb:9
           spec/double_entry/configuration_spec.rb:10
         ],
         description: 'Unconfigured, the three max lengths are nil, json_metadata is false ' \
                      'and running_inside_transactional_fixtures is false.' do
  get_config key: 'code_max_length'
  get_config key: 'scope_identifier_max_length'
  get_config key: 'account_identifier_max_length'
  get_config key: 'json_metadata'
  get_config key: 'running_inside_transactional_fixtures'
end

scenario 'configuration-max-lengths-set',
         area: 'configuration',
         source: %w[
           spec/double_entry/configuration_spec.rb:15
           spec/double_entry/configuration_spec.rb:20
           spec/double_entry/configuration_spec.rb:25
         ],
         description: 'A max length that has been set is read back as set: code 10, ' \
                      'scope identifier 11, account identifier 9.' do
  set_config key: 'code_max_length', value: 10
  get_config key: 'code_max_length'
  set_config key: 'scope_identifier_max_length', value: 11
  get_config key: 'scope_identifier_max_length'
  set_config key: 'account_identifier_max_length', value: 9
  get_config key: 'account_identifier_max_length'
end
