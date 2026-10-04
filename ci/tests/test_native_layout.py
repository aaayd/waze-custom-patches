import copy
import json
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import native_layout
from native_icons import CATALOG, TEMPLATES


class ReportDiscoveryTests(unittest.TestCase):
    def setUp(self):
        self.catalog = json.loads(CATALOG.read_text())
        self.stream = []
        for group in self.catalog['groups'][:8]:
            for name in group:
                if not name.startswith('map_pins_report_'):
                    self.stream.append(dict(call=len(self.stream) * 4, target=100, name=name, count=None))
            self.stream.append(dict(call=len(self.stream) * 4, target=200, name=None, count=3))
            self.stream.append(None)

    def discover(self, stream):
        with patch.object(native_layout, 'candidate_ranges', return_value=[(0, 100)]), \
                patch.object(native_layout, 'calls_in', return_value=iter(stream)), \
                patch.object(native_layout, 'verify_builder', return_value=32):
            return native_layout.discover_layout(object(), TEMPLATES, self.catalog)

    def test_discovers_verified_compact_groups(self):
        constructor, builder, groups = self.discover(self.stream)
        self.assertEqual((constructor, builder, len(groups)), (100, 200, 8))

    def test_rejects_two_live_constructors(self):
        other = copy.deepcopy(self.stream)
        for call in other:
            if call and call['target'] == 100:
                call['target'] = 101
        with self.assertRaisesRegex(ValueError, 'one shared report string constructor'):
            self.discover(self.stream + other)

    def test_rejects_two_live_builders(self):
        other = copy.deepcopy(self.stream)
        other[3]['target'] = 201
        with self.assertRaisesRegex(ValueError, 'ambiguous or incomplete report tables'):
            self.discover(other)

    def test_rejects_missing_subtype_even_with_valid_helpers(self):
        other = copy.deepcopy(self.stream)
        del other[2]
        with self.assertRaisesRegex(ValueError, 'count disagrees'):
            self.discover(other)

    def test_requires_artwork_mapping_for_new_subtype(self):
        other = copy.deepcopy(self.stream)
        other[2]['name'] = 'bigpin_unreviewed_new_hazard'
        with self.assertRaisesRegex(ValueError, 'unrecognised report group'):
            self.discover(other)

    def test_rejects_control_flow_inside_group(self):
        other = copy.deepcopy(self.stream)
        other.insert(2, None)
        with self.assertRaisesRegex(ValueError, 'control flow crosses'):
            self.discover(other)


if __name__ == '__main__':
    unittest.main()
