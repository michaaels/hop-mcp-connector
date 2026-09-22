# PokeAPI ability files

This Apache Hop pipeline downloads one ability from PokeAPI and creates:

- `output/<ability>.json`: original API response.
- `output/<ability>-pokemon.csv`: normalized Pokémon records for that ability.

Defaults:

- `API_URL=https://pokeapi.co/api/v2/ability/battle-armor`
- `ABILITY_SLUG=battle-armor`

Both values are pipeline parameters, so the same definition can process another ability without editing the pipeline.
