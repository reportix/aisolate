import sys

from aisolate.build import main

if __name__ == "__main__":
    # `python -m aisolate build ...`: the only command.
    sys.exit(main(sys.argv[2:] if sys.argv[1:2] == ["build"] else ["--help"]))
