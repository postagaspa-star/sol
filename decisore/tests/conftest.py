import sys
from pathlib import Path

# La cartella decisore/ contiene il pacchetto `app` e i finti dei test.
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
sys.path.insert(0, str(Path(__file__).resolve().parent))
