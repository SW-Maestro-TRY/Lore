"""Zero-cost CLI-chain checks. Model outputs are explicit fixtures, never generated art.

Runs the real argument parser/stages in child processes with model, image, network,
and character-regeneration entry points guarded. Optional SERIES_DRY_RUN_ARTIFACTS
keeps prompts for inspection. This is not a browser-to-server end-to-end test.
"""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
GUARDED_CLI = '''
import socket, sys

def forbidden(*args, **kwargs):
    raise AssertionError("dry-run attempted network, model, or character regeneration")
socket.socket.connect = forbidden
socket.create_connection = forbidden
import run, own, llm, imagegen
llm.Call = forbidden
imagegen.paint = forbidden
own.stage_persona = forbidden
own.stage_cast = forbidden
own.stage_sheet = forbidden
raise SystemExit(run.main(sys.argv[1:]))
'''


class SeriesCliTest(unittest.TestCase):
    def setUp(self):
        parent = os.environ.get('SERIES_DRY_RUN_ARTIFACTS')
        if parent:
            Path(parent).mkdir(parents=True, exist_ok=True)
            self.root = Path(tempfile.mkdtemp(prefix='cli-chain-', dir=parent))
        else:
            temp = tempfile.TemporaryDirectory()
            self.addCleanup(temp.cleanup)
            self.root = Path(temp.name)
        self.env = {**os.environ, 'NH_RUNS_DIR': str(self.root), 'LOGFIRE_TOKEN': '',
                    'PYTHONDONTWRITEBYTECODE': '1'}
        self.book = {'name': '야간 우체국', 'confirmed': True,
                     'fixed_settings': '오래된 고정 설정', 'progress_state': '오래된 시작 상태',
                     'entries': [{'title': '편지', 'content': '편지는 달빛 아래서만 읽힌다',
                                  'enabled': True, 'always': True, 'keys': []}]}
        self.source = self.root / 'ep1'
        self.source.mkdir()
        self.put(self.source / 'input.json', {'name': '하린', 'description': '푸른 외투의 우편배달부',
                 'genre': '판타지', 'lorebook': self.book})
        self.put(self.source / 'persona.json', {'name': '하린'})
        self.put(self.source / 'cast.json', [{'name': '서우', 'look': '흰 머리와 검은 코트'}])
        (self.source / 'sheet.png').write_bytes(b'fixture-original-hero-sheet')
        (self.source / 'sheets').mkdir()
        (self.source / 'sheets' / '서우.png').write_bytes(b'fixture-original-cast-sheet')
        self.finished_fixture(self.source, 1)

    @staticmethod
    def put(path, obj):
        path.write_text(json.dumps(obj, ensure_ascii=False, indent=2), encoding='utf-8')

    def finished_fixture(self, folder, number):
        body = f'확정된 {number}화 사건: 하린이 서우에게 편지 {number}통을 건넨다.'
        self.put(folder / 'directions.json', [{'n': 1, 'title': f'달빛 편지 {number}',
                 'genre': '판타지', 'intro': '달빛 우체국의 배달부', 'body': body}])
        self.put(folder / 'pick.json', {'n': 1})
        self.put(folder / 'scenes.json', {'cast': [{'name': '서우', 'appearance': '흰 머리와 검은 코트'}],
            'fixed': ['우체국 — 푸른 문'], 'scenes': [{'n': i, 'where': '달빛 우체국',
            'what': f'{number}화 장면{i}: 하린이 서우에게 편지를 건넨다.', 'acting': '하린이 손을 내민다.',
            'prev': '편지를 쥔 상태', 'ends': '서우가 편지를 받았다.', 'look': '시트 그대로'} for i in range(1, 5)]})

    def cli(self, *args, success=True):
        result = subprocess.run([sys.executable, '-c', GUARDED_CLI, *args, '--dry-run'],
            cwd=HERE, env=self.env, text=True, capture_output=True, timeout=60)
        with (self.root / 'cli.log').open('a') as log:
            log.write(' '.join(args) + '\n' + result.stdout + result.stderr + '\n')
        if success:
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        else:
            self.assertNotEqual(result.returncode, 0)
        return result

    def inputs(self, number, source):
        fixed = f'고정 수정 {number}: 하린은 거짓말을 할 수 없다.'
        state = f'현재 수정 {number}: 하린과 서우는 화해한 상태다.'
        request = f'사용자 요청 {number}: 서우가 편지를 열고 함께 우체국으로 돌아간다.'
        packet = self.root / f'packet{number}.json'
        character = self.root / f'character{number}.json'
        self.put(packet, {'source_dir': str(source), 'source_run': source.name,
            'episode_number': number, 'fixed_settings': fixed, 'progress_state': state,
            'source_scenes': [f'{number-1}화에서 작가가 수정한 마지막 장면'], 'request': request})
        self.put(character, {'name': '하린', 'character': '푸른 외투의 우편배달부',
            'story': request, 'settings': fixed, 'lorebook': self.book})
        return packet, character, fixed, state, request

    def test_episodes_two_three_four_reuse_sheets_and_current_user_inputs(self):
        expected = {p.relative_to(self.source): hashlib.sha256(p.read_bytes()).hexdigest()
                    for p in self.source.rglob('*.png')}
        source = self.source
        for number in (2, 3, 4):
            with self.subTest(episode=number):
                packet, character, fixed, state, request = self.inputs(number, source)
                run_id = f'ep{number}'
                folder = self.root / run_id
                self.cli('--run-id', run_id, '--character', str(character), '--continuation', str(packet), '--own')
                story = (folder / 'story_prompt.txt').read_text()
                for text in (f'{number}화', fixed, state, request, f'확정된 {number-1}화 사건', f'{number-1}화에서 작가가 수정한 마지막 장면'):
                    self.assertIn(text, story)
                self.assertNotIn('오래된 시작 상태', story)
                self.assertNotIn('오래된 고정 설정', story)
                self.assertFalse((folder / 'directions.json').exists(), 'dry run must not pretend to generate a story')
                # Explicit fixture at the model boundary; never presented as generated output.
                self.finished_fixture(folder, number)
                self.cli('--run-id', run_id, '--own-restory', '--note', f'{number}화 사용자 재작성 메모')
                self.assertIn(f'{number}화 사용자 재작성 메모', (folder / 'story_prompt.txt').read_text())
                self.cli('--run-id', run_id, '--own-scenes')
                scene = (folder / 'scene_prompt.txt').read_text()
                for text in (fixed, state, request, f'확정된 {number}화 사건'):
                    self.assertIn(text, scene)
                self.assertNotIn(f'확정된 {number-1}화 사건', scene)
                self.cli('--run-id', run_id, '--detail-pages')
                prompts = list((folder / 'pages').glob('*.txt'))
                self.assertEqual(len(prompts), 5)
                for prompt in prompts:
                    text = prompt.read_text()
                    self.assertIn(fixed, text)
                    self.assertIn(state, text)
                    self.assertIn('편지는 달빛 아래서만 읽힌다', text)
                for path, digest in expected.items():
                    self.assertEqual(hashlib.sha256((folder / path).read_bytes()).hexdigest(), digest)
                self.assertFalse(list((folder / 'pages').glob('*.png')))
                for name in ('persona_prompt.txt', 'cast_prompt.txt', 'sheet_prompt.txt', 'meta.json'):
                    self.assertFalse((folder / name).exists(), name)
                source = folder
        print(f'CLI 2→3→4 story/scene/page prompts and unchanged sheets PASS: {self.root}')

    def test_initial_story_without_input_with_multiple_books_and_after_detach(self):
        second = {'name': '인물 규칙', 'confirmed': True, 'fixed_settings': '서우는 시간을 멈출 수 없다',
                  'entries': [{'title': '종', 'content': '종은 매일 자정에 세 번 울린다',
                               'enabled': True, 'always': True, 'keys': []}]}
        cases = [('no-book', None, False, False),
                 ('two-books', {'confirmed': True, 'books': [self.book, second]}, True, True),
                 ('removed-first', second, False, True), ('removed-all', None, False, False)]
        for name, book, has_first, has_second in cases:
            with self.subTest(case=name):
                character = self.root / (name + '.json')
                self.put(character, {'name': '하린', 'character': '푸른 외투의 우편배달부',
                                     'genre': '판타지', 'story': '', 'lorebook': book})
                self.cli('--run-id', name, '--character', str(character))
                text = (self.root / name / 'story_prompt.txt').read_text()
                self.assertEqual('편지는 달빛 아래서만 읽힌다' in text, has_first)
                self.assertEqual('서우는 시간을 멈출 수 없다' in text, has_second)
                self.assertEqual('종은 매일 자정에 세 번 울린다' in text, has_second)

    def test_detached_book_is_absent_in_every_continuation_prompt(self):
        packet, character, fixed, state, request = self.inputs(2, self.source)
        char = json.loads(character.read_text())
        char['lorebook'] = None
        self.put(character, char)
        self.cli('--run-id', 'detached', '--character', str(character), '--continuation', str(packet), '--own')
        folder = self.root / 'detached'
        self.finished_fixture(folder, 2)
        self.cli('--run-id', 'detached', '--own-scenes')
        self.cli('--run-id', 'detached', '--detail-pages')
        for path in [folder / 'story_prompt.txt', folder / 'scene_prompt.txt', *list((folder / 'pages').glob('*.txt'))]:
            text = path.read_text()
            self.assertNotIn('편지는 달빛 아래서만 읽힌다', text)
            self.assertNotIn('오래된 고정 설정', text)
            self.assertIn(fixed, text)
            self.assertIn(state, text)

    def test_missing_original_sheet_stops_without_fallback(self):
        (self.source / 'sheet.png').unlink()
        packet, character, *_ = self.inputs(2, self.source)
        result = self.cli('--run-id', 'missing', '--character', str(character), '--continuation', str(packet), '--own', success=False)
        self.assertIn('앞 화의 캐릭터 시트가 없습니다', result.stderr)
        self.assertFalse((self.root / 'missing' / 'story_prompt.txt').exists())


if __name__ == '__main__':
    unittest.main()
