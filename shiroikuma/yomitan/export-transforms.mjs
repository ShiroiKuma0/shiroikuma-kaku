// Export Yomitan's Japanese deinflection rules (ext/js/language/ja/japanese-transforms.js, GPL-3.0,
// Yomitan Authors) as plain JSON for 白い熊 画's Kotlin port (shiroikuma.kaku.dict.Deinflector).
// Every rule there is suffixInflection or wholeWordInflection, so a rule is fully described by its
// inflected / deinflected strings and its condition names.
//
// Usage: node shiroikuma/yomitan/export-transforms.mjs [path to a Yomitan checkout]
//        (default ~/git/shiroikuma-kako-yomitan) → app/src/main/assets/yomitan/japanese-transforms.json
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import {execSync} from 'node:child_process';
import {fileURLToPath, pathToFileURL} from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '../..');
const yomitan = process.argv[2] || path.join(os.homedir(), 'git/shiroikuma-kako-yomitan');
const src = path.join(yomitan, 'ext/js/language/ja/japanese-transforms.js');
const {japaneseTransforms} = await import(pathToFileURL(src).href);

const out = {
    source: 'Yomitan ext/js/language/ja/japanese-transforms.js',
    yomitanCommit: execSync('git rev-parse --short HEAD', {cwd: yomitan}).toString().trim(),
    license: 'GPL-3.0-or-later, Copyright (C) 2024-2026 Yomitan Authors',
    conditions: {},
    transforms: [],
};
for (const [type, c] of Object.entries(japaneseTransforms.conditions)) {
    out.conditions[type] = {
        name: c.name,
        isDictionaryForm: !!c.isDictionaryForm,
        ...(c.subConditions ? {subConditions: c.subConditions} : {}),
    };
}
for (const [id, t] of Object.entries(japaneseTransforms.transforms)) {
    const rules = t.rules.map((r) => {
        if (r.type === 'suffix') {
            const inflected = r.isInflected.source.replace(/\$$/, '');
            return {type: 'suffix', inflected, deinflected: r.deinflected, conditionsIn: r.conditionsIn, conditionsOut: r.conditionsOut};
        }
        if (r.type === 'wholeWord') {
            const inflected = r.isInflected.source.replace(/^\^/, '').replace(/\$$/, '');
            return {type: 'wholeWord', inflected, deinflected: r.deinflect(''), conditionsIn: r.conditionsIn, conditionsOut: r.conditionsOut};
        }
        throw new Error(`unsupported rule type ${r.type} in ${id}`);
    });
    for (const r of rules) {
        if (/[\\^$.*+?()[\]{}|]/.test(r.inflected)) throw new Error(`regex metacharacter in ${id}: ${r.inflected}`);
    }
    out.transforms.push({id, name: t.name, ...(t.description ? {description: t.description} : {}), rules});
}
const dest = path.join(root, 'app/src/main/assets/yomitan/japanese-transforms.json');
fs.mkdirSync(path.dirname(dest), {recursive: true});
fs.writeFileSync(dest, JSON.stringify(out));
console.log(`>>> ${dest}: ${Object.keys(out.conditions).length} conditions, ${out.transforms.length} transforms, ` +
    `${out.transforms.reduce((n, t) => n + t.rules.length, 0)} rules (Yomitan ${out.yomitanCommit})`);
