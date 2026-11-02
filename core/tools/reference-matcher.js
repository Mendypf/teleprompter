"use strict";
// The reference word-following rules, in JavaScript. Matcher.kt and Script.toWords must behave exactly like this.
//
// make-golden.js runs these rules over many heard sentences and writes down where they land (golden.txt); GoldenTest
// then checks that the Kotlin code lands on the same words. The rules between MATCHER START and MATCHER END are the
// original code, unchanged. Only the lines around them were added so they can be loaded from a script.
//
// SCRIPT is the script as one string. The rules work on a script that gives exactly one spoken word per shown word
// (a shown word like "10%" would be joined into one key here, while the app keeps two keys for it).
//
// loadRules(SCRIPT) returns { advance, toWords, WORDS, KEYS, NEAR, FAR }.

function loadRules(SCRIPT) {
  const WORDS = SCRIPT.split(/\s+/);
  /* MATCHER START: the few rules the app uses. */
  const ONES = ["zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve",
    "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen"];
  const TENS = ["", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"];
  const spell = (n) => (n < 20 ? ONES[n] : TENS[Math.floor(n / 10)] + (n % 10 ? " " + ONES[n % 10] : ""));
  const toWords = (text) => text.toLowerCase()
    .replace(/%/g, " percent")
    .replace(/\b\d{1,2}\b/g, (d) => spell(Number(d)))
    .replace(/['’]/g, "")
    .split(/[^a-z0-9]+/)
    .filter(Boolean);
  const KEYS = WORDS.map((w) => toWords(w).join(""));
  const NEAR = 12, FAR = 60;

  function find(seq, from, to) {
    for (let at = Math.max(0, from); at < Math.min(to, KEYS.length - seq.length + 1); at++) {
      if (seq.every((w, k) => KEYS[at + k] === w)) return at;
    }
    return -1;
  }

  // pos = the next script word you have not said yet. heard = the words speech-to-text just wrote down.
  function advance(pos, heard) {
    const tail = [];
    for (const w of heard) {
      tail.push(w);
      if (tail.length > 3) tail.shift();
      if (KEYS[pos] === w) { pos += 1; continue; }              // the next word: move on one
      if (tail.length >= 2) {                                    // two in a row a little further on: you skipped a word
        const at = find(tail.slice(-2), pos, pos + NEAR);
        if (at >= 0) { pos = at + 2; continue; }
      }
      if (tail.length === 3) {                                   // three in a row much further on: you skipped a sentence
        const at = find(tail, pos, pos + FAR);
        if (at >= 0) { pos = at + 3; continue; }
      }
      // anything else (a repeat, an "um", a misheard word) leaves the text where it is
    }
    return pos;
  }
  /* MATCHER END */
  return { advance, toWords, WORDS, KEYS, NEAR, FAR };
}

module.exports = { loadRules };
