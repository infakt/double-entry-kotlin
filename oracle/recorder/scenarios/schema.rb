# frozen_string_literal: true

# The tables the generator's migration creates
# (lib/generators/double_entry/install/templates/migration.rb), rendered for
# Rails 8.0, with and without json_metadata. The generator itself is not
# ported (decision 0001 §5); its spec is the closest example.

scenario 'schema-json-metadata',
         area: 'schema',
         source: 'spec/generators/double_entry/install/install_generator_spec.rb:73',
         schema: { shape: 'generator', json_metadata: true },
         description: 'With json_metadata the migration adds a json metadata column to the lines ' \
                      'and creates no line_metadata table.' do
  describe_schema
end

scenario 'schema-metadata-table',
         area: 'schema',
         source: 'spec/generators/double_entry/install/install_generator_spec.rb:94',
         schema: { shape: 'generator', json_metadata: false },
         description: 'Without json_metadata the migration creates the line_metadata table ' \
                      'and the lines have no metadata column.' do
  describe_schema
end
