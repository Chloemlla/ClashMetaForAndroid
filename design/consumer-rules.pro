# B-70: design ships no reflection / serialization / JNI entry points.
# ViewBinding / DataBinding classes are generated per-module and referenced from
# compiled code (R8 keeps them via normal reachability); no keep rules needed.
