#!/usr/bin/env -S python
import numpy as np

print(f"NumPy version: {np.__version__}")

rng = np.random.default_rng(42)
state_dict = rng.bit_generator.state

print(f"\nActual state: {state_dict['state']['state']}")
print(f"Actual inc:   {state_dict['state']['inc']}")

# Check if it's the standard PCG64 increment
standard_inc = 332724090758049132448979897138935081983
print(f"\nStandard PCG64 increment: {standard_inc}")
print(f"Using standard increment: {state_dict['state']['inc'] == standard_inc}")

# If NumPy uses a fixed increment, then SeedSequence only sets the state, not inc
# In that case, we just need to figure out how seed=42 -> state=274674114334540486603088602300644985544
