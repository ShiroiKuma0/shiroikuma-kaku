// Reference deinflections from Yomitan's own LanguageTransformer, for DeinflectorTest (which
// checks the Kotlin port gives exactly the same text / conditions / trace for every word).
// Usage: node shiroikuma/yomitan/reference-deinflections.mjs [Yomitan checkout]
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import {fileURLToPath, pathToFileURL} from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '../..');
const yomitan = process.argv[2] || path.join(os.homedir(), 'git/shiroikuma-kako-yomitan');
const {LanguageTransformer} = await import(pathToFileURL(path.join(yomitan, 'ext/js/language/language-transformer.js')).href);
const {japaneseTransforms} = await import(pathToFileURL(path.join(yomitan, 'ext/js/language/ja/japanese-transforms.js')).href);
const lt = new LanguageTransformer();
lt.addDescriptor(japaneseTransforms);
const words = ['食べた', '食べさせられなかった', '行って', '来ない', 'しました', '読まれている', '高くなかった', '静かな',
    '書けば', '飲みたくない', 'いらっしゃいます', '見せてください', '走っちゃった', '言わなきゃ', '寒さ', '問うた', '行った'];
const out = {};
for (const w of words) {
    out[w] = lt.transform(w).map((r) => `${r.text}|${r.conditions}|${r.trace.map((f) => f.transform).join(',')}`);
}
const dest = path.join(root, 'app/src/test/resources/deinflect-expected.json');
fs.writeFileSync(dest, JSON.stringify(out, null, 1));
console.log(`>>> ${dest}: ${words.length} words, ${Object.values(out).reduce((n, a) => n + a.length, 0)} results`);
