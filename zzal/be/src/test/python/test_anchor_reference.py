"""분리된 격자점과 층별 캔버스가 앵커 기준을 깨뜨리지 않는지 검증한다."""
import copy
import json
import sys
import tempfile
import unittest
from pathlib import Path

import numpy as np
from PIL import Image

PIPE = Path(__file__).resolve().parents[2] / 'main/resources/zzal/pipeline/v1'
sys.path.insert(0, str(PIPE))
import anchors


class AnchorReferenceTest(unittest.TestCase):
    def image(self, with_mark=False):
        pixels = np.zeros((366, 312, 4), dtype=np.uint8)
        pixels[45:302, 90:204] = [210, 210, 210, 255]
        if with_mark:
            pixels[4:28, 18:22] = [255, 0, 255, 255]
            pixels[14:18, 8:32] = [255, 0, 255, 255]
        return Image.fromarray(pixels)

    def test_detached_mark_cannot_change_reference(self):
        clean, marked = self.image(), self.image(True)
        before = marked.tobytes()
        self.assertEqual(anchors.base_scale(marked), (257, 114))
        self.assertEqual(anchors.base_scale(marked), anchors.base_scale(clean))
        self.assertEqual(marked.tobytes(), before)

    def test_base_pose_uses_same_body_as_reference(self):
        result = anchors.build({'base': self.image(True)}, ['base'])
        self.assertEqual(result['poses']['base']['head_top']['y'], 45)
        self.assertEqual(result['poses']['base']['bbox']['h'], result['K'])

    def test_empty_reference_still_rejected(self):
        with self.assertRaises(anchors.AnchorError):
            anchors.base_scale(Image.new('RGBA', (10, 10)))

    def merge(self, old, new):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / 'anchors.json'
            p.write_text(json.dumps(old))
            return anchors.merge_into(p, new)

    def data(self, h, keys):
        result = anchors.build({'base': self.image()}, ['base'])
        pose = result['poses']['base']
        result['canvas']['h'] = h
        result['poses'] = {key: dict(copy.deepcopy(pose), canvas=[312, h]) for key in keys}
        return result

    def test_different_layer_canvas_keeps_sixteen_poses(self):
        old = self.data(366, ['base'] + [f'a{x}' for x in range(7)])
        new = self.data(424, [f'b{x}' for x in range(8)])
        result = self.merge(old, new)
        self.assertEqual(len(result['poses']), 16)
        self.assertEqual(result['canvas'], old['canvas'])
        self.assertEqual(result['poses']['base']['canvas'], [312, 366])
        self.assertEqual(result['poses']['b0']['canvas'], [312, 424])

    def test_legacy_pose_gets_its_original_canvas(self):
        old, new = self.data(366, ['base']), self.data(424, ['wash'])
        del old['poses']['base']['canvas']
        result = self.merge(old, new)
        self.assertEqual(result['poses']['base']['canvas'], [312, 366])
        self.assertNotIn('canvas', old['poses']['base'])

    def test_changed_body_scale_does_not_reuse_old_poses(self):
        old, new = self.data(366, ['base']), self.data(424, ['wash'])
        new['Hw'] += 1
        self.assertEqual(self.merge(old, new), new)

    def test_repaired_pose_is_removed_from_missing(self):
        old, new = self.data(366, ['base', 'wash']), self.data(424, ['wash'])
        old['poses']['wash'] = None
        old['missing'] = ['wash']
        result = self.merge(old, new)
        self.assertNotIn('missing', result)
        self.assertIsNotNone(result['poses']['wash'])


if __name__ == '__main__':
    unittest.main()
