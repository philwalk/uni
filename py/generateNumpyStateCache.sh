#!/bin/bash
for i in {0..124}; do 
  python -c "import numpy as np; rng = np.random.default_rng($i); s = rng.bit_generator.state['state']; print(f'$i={s[\"state\"]},{s[\"inc\"]}')"
done > ~/.numpy_rng_cache.txt
