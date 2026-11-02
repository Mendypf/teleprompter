"use strict";
// Makes core/src/test/resources/golden.txt: heard sentences, each with the reference rules' own answer.
//
// reference-matcher.js holds the word-following rules the app must copy exactly. This tool runs those rules over a
// few hundred heard sentences on three scripts (reading straight, repeats, "um", skipped words, skipped sentences,
// misheard words, pauses, starting in the middle, running past the end) and writes where the rules land for each one.
// GoldenTest.kt then checks that the app lands on the same word. The first script is Samples.SAMPLE_SCRIPT, read
// from core/src/test/kotlin/.../Samples.kt; the other two are made from it. Every script here gives exactly one
// spoken word per shown word, because that is where the reference and the app agree (the reference joins "10%" into
// one key, the app keeps "ten" and "percent").
//
// Run from the project folder. The same file comes out every time, so a changed file means the rules or the script
// changed.
//   node core/tools/make-golden.js [output file]

const fs = require("fs");
const path = require("path");
const { loadRules } = require("./reference-matcher.js");

const outPath = process.argv[2] || path.join(__dirname, "..", "src", "test", "resources", "golden.txt");
const samplesPath = path.join(__dirname, "..", "src", "test", "kotlin", "io", "github", "mendypf", "prompter", "core", "Samples.kt");

// The same rules with a counter on each kind of move, only to report how often each rule fired.
const countedSource = loadRules.toString()
  .replace("pos += 1;", "COUNT.next++; pos += 1;")
  .replace("pos = at + 2;", "COUNT.near++; pos = at + 2;")
  .replace("pos = at + 3;", "COUNT.far++; pos = at + 3;");
if ((countedSource.match(/COUNT\./g) || []).length !== 3) throw new Error("the reference rules changed shape; update the counters");
const makeCounted = new Function("COUNT", "return " + countedSource);

// SAMPLE_SCRIPT from Samples.kt: its quoted pieces, joined.
function sampleScript() {
  const source = fs.readFileSync(samplesPath, "utf8");
  const block = source.split("const val SAMPLE_SCRIPT =")[1].split(/\r?\n\s*\r?\n/)[0];
  const pieces = [...block.matchAll(/"([^"\\]*)"/g)].map((m) => m[1]);
  const joined = pieces.join("");
  if (!pieces.length || joined.includes("$")) throw new Error("could not read SAMPLE_SCRIPT from Samples.kt");
  return joined;
}
const baseScript = sampleScript();

// Random numbers from a fixed seed (mulberry32), so every run makes the same file.
let seed = 123456789;
function rand() {
  seed = (seed + 0x6d2b79f5) | 0;
  let t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
  t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
  return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
}
const int = (n) => Math.floor(rand() * n);
const chance = (p) => rand() < p;
const pick = (list) => list[int(list.length)];
const shuffled = (list) => {
  const a = list.slice();
  for (let i = a.length - 1; i > 0; i--) {
    const j = int(i + 1);
    [a[i], a[j]] = [a[j], a[i]];
  }
  return a;
};

// Three scripts: the sample script; its sentences shuffled three times over (many repeated phrases);
// common words in random order (many look-alike spots for the skip rules to find).
const sentences = baseScript.match(/[^.!?]+[.!?]+/g).map((s) => s.trim());
const COMMON = ["the", "The", "it", "it's", "It's", "you", "You", "that", "and", "to", "a", "is", "of", "on", "its",
  "dough", "time", "times", "can", "only", "do", "it.", "you.", "way", "what's", "don't", "more", "than", "first"];
const SCRIPTS = [
  baseScript,
  [1, 2, 3].flatMap(() => shuffled(sentences)).join(" "),
  Array.from({ length: 300 }, () => pick(COMMON)).join(" "),
];

// How the speech AI may write a spoken word.
const WRITTEN = {
  its: ["it's", "it’s", "It's"], ive: ["I've", "I’ve"], youre: ["you're", "you’re"], dont: ["don't", "don’t"],
  whats: ["what's", "what’s"], wont: ["won't", "won’t"], ten: ["10"], five: ["5"], eighty: ["80"], twenty: ["20"],
};
function write(words) {
  const out = [];
  for (let i = 0; i < words.length; i++) {
    let w = words[i];
    if (WRITTEN[w] && chance(0.5)) w = pick(WRITTEN[w]);
    if (/^[0-9]+$/.test(w) && words[i + 1] === "percent" && chance(0.6)) { // "80%"
      w += "%";
      i++;
    }
    if (chance(0.05)) w = w.charAt(0).toUpperCase() + w.slice(1);
    if (chance(0.1)) w += pick([",", ".", "?", "!", "...", ";", ":"]);
    out.push(w);
  }
  return out.join(" ");
}

const FILLERS = ["um", "uh", "um,", "uh...", "hmm", "er", "like", "so", "you know", "I mean"];
const MISHEARD = ["break", "brake", "bred", "flesh", "though", "doe", "sing", "four", "for", "too", "two", "there",
  "their", "a", "i", "sag", "stay", "times", "peoples", "in", "and"];
const PAUSES = ["", " ", "...", "?!", "—", "- -", "’", "um", "uh um", "Um.", "hmm..."];
const KINDS = ["straight", "repeats", "part words", "phrase repeats", "fillers", "skipped words", "skipped sentence",
  "misheard", "going back", "random words", "pause", "past the end", "everything"];

// The words someone says, starting at script word `pos`.
function heard(keys, pos, kind) {
  const say = (from, n) => keys.slice(Math.max(0, from), Math.max(0, from) + n);
  switch (kind) {
    case "straight": return say(pos, 1 + int(20));
    case "repeats": return say(pos, 2 + int(14)).flatMap((w) => (chance(0.3) ? Array(2 + int(3)).fill(w) : [w]));
    case "part words": return say(pos, 2 + int(12)).flatMap((w) => (chance(0.35) ? [w.slice(0, 1 + int(2)) + "-", w] : [w]));
    case "phrase repeats": {
      const out = [];
      for (const w of say(pos, 3 + int(15))) {
        out.push(w);
        if (out.length >= 2 && chance(0.25)) out.push(...out.slice(-(2 + int(2))));
      }
      return out;
    }
    case "fillers": return say(pos, 2 + int(15)).flatMap((w) => (chance(0.25) ? [pick(FILLERS), w] : [w]));
    case "skipped words": return say(pos, 3 + int(20)).filter(() => !chance(0.2));
    case "skipped sentence": return [...say(pos, int(6)), ...say(pos + 4 + int(75), 1 + int(10))];
    case "misheard": return say(pos, 2 + int(15)).map((w) => (chance(0.25) ? (chance(0.5) ? pick(MISHEARD) : pick(keys)) : w));
    case "going back": return say(pos - 1 - int(30), 1 + int(15));
    case "random words": return Array.from({ length: 1 + int(12) }, () => pick(keys));
    case "past the end": return [...say(pos, 20), ...Array.from({ length: 1 + int(6) }, () => pick(keys))];
    case "everything": {
      const out = [];
      let at = pos;
      for (let n = 3 + int(4); n > 0; n--) {
        const part = heard(keys, at, pick(KINDS.filter((k) => k !== "everything" && k !== "pause")));
        out.push(...part);
        at += part.length + (chance(0.3) ? 5 + int(40) : 0);
      }
      return out;
    }
    default: throw new Error("unknown kind " + kind);
  }
}

function startFor(keys, kind) {
  if (kind === "past the end") return keys.length - int(10);
  const r = rand();
  if (r < 0.1) return 0;
  if (r < 0.15) return keys.length;
  if (r < 0.25) return keys.length - 1 - int(10);
  return int(keys.length + 1);
}

// Text for the toWords check: hand-picked edge cases, then random strings of awkward characters.
const TRICKY = [
  "Hello, world!", "10% of AI's users", "it's 1.5 GB", "It’s", "don’t", "I've", "‘tis", "rock'n'roll", "’90s", "90's",
  "100", "007", "07", "0", "00", "5", "21", "20", "99", "100%", "5%", "%5", "50%off", "5%%", "1'2", "1’2", "a1", "1a",
  "_5", "5_", "x 7 y", "7.", "(12)", "#1", "$5", "5:30", "1,000", "2026", "3rd", "1st place", "e-mail", "A-B", "—",
  "...", "", "   ", " nbsp word", "ÉCOLE 12", "naïve 5", "straße", "İstanbul", "ΣΑΣ 4", "٣", "１２", "Ⅻ",
  "K9", "über9", "9über", "café's", "WHAT'S UP", "twenty-one", "21st", "1.5.2", "3/4", "50/50", "4x4", "covid-19",
  "B2B", "R2-D2", "mp3", "a.m.", "U.S.A.", "e.g.", "it''s", "''", "’’", "%", "%%", "5 %", "1 2 3", "um, uh... so",
];
const CHARS = "abcxyzABCXYZ0123456789 '’%_-.,:/—é‘“” İKß";
const randomText = () => Array.from({ length: 1 + int(14) }, () => pick(CHARS)).join("");

// One line per item, fields split by tabs, so the Kotlin test needs no extra library to read it.
const field = (s) => {
  if (/[\t\n\r]/.test(s)) throw new Error("a field may not hold a tab or a line break: " + JSON.stringify(s));
  return s;
};
const first = loadRules(baseScript);
const out = [
  "# Made by core/tools/make-golden.js from core/tools/reference-matcher.js. Do not edit by hand.",
  "# near/far: the reference distances. words: a text, then the reference spoken words for it.",
  "# script: a script; keys: its spoken words. case: start position, heard text, the reference spoken words for it,",
  "# where the reference lands. end: how many case and words lines there are.",
  "near\t" + first.NEAR,
  "far\t" + first.FAR,
];
let wordLines = 0;
for (const text of [...TRICKY, ...Array.from({ length: 120 }, randomText)]) {
  out.push("words\t" + field(text) + "\t" + first.toWords(text).join(" "));
  wordLines++;
}

let caseLines = 0;
for (const script of SCRIPTS) {
  const m = loadRules(script);
  const count = { next: 0, near: 0, far: 0 };
  const c = makeCounted(count)(script);
  const odd = m.WORDS.filter((w) => m.toWords(w).length !== 1);
  if (odd.length) throw new Error("these words do not give exactly one spoken word: " + odd.join(" "));
  out.push("script\t" + field(script));
  out.push("keys\t" + m.KEYS.join(" "));
  let held = 0;
  for (let n = 0; n < 170; n++) {
    const kind = pick(KINDS);
    const pos = startFor(m.KEYS, kind);
    const text = kind === "pause" ? pick(PAUSES) : write(heard(m.KEYS, pos, kind));
    const words = m.toWords(text);
    const lands = m.advance(pos, words);
    if (c.advance(pos, words) !== lands) throw new Error("the counted copy disagrees with the reference");
    if (lands === pos) held++;
    out.push(["case", pos, field(text), words.join(" "), lands].join("\t"));
    caseLines++;
  }
  // Skips that end right at the edge of the NEAR and FAR windows, where a window one word too big or small shows.
  let edges = 0;
  for (let n = 0; n < 30; n++) {
    const size = n % 2 === 0 ? 2 : 3;
    const gap = size === 2 ? m.NEAR - 2 + int(4) : m.FAR - 3 + int(5);
    const pos = int(m.KEYS.length - gap - size);
    const text = m.KEYS.slice(pos + gap, pos + gap + size).join(" ");
    const lands = m.advance(pos, m.toWords(text));
    if (c.advance(pos, m.toWords(text)) !== lands) throw new Error("the counted copy disagrees with the reference");
    if (lands !== pos) edges++;
    out.push(["case", pos, field(text), m.toWords(text).join(" "), lands].join("\t"));
    caseLines++;
  }
  console.log(`script of ${m.KEYS.length} words: 200 cases, ${held} held still; moves: next word ${count.next}, ` +
    `skipped word ${count.near}, skipped sentence ${count.far}; ${edges} of 30 edge cases moved`);
}
out.push("end\t" + caseLines + "\t" + wordLines);

fs.mkdirSync(path.dirname(outPath), { recursive: true });
fs.writeFileSync(outPath, out.join("\n") + "\n", "utf8");
console.log(`wrote ${caseLines} cases and ${wordLines} word checks to ${outPath}`);
