import os
import unittest
import tempfile

from diff_rulekeys import *


class MockFile(object):
    def __init__(self, lines):
        self._lines = lines

    def readlines(self):
        return self._lines


class TestRuleKeyDiff(unittest.TestCase):
    def test_key_value_diff(self):
        list_diff = KeyValueDiff()
        list_diff.append('l', 'r')
        self.assertEqual(list_diff.diff(), ['-[l]', '+[r]'])

    def test_key_value_diff_with_common_elements(self):
        list_diff = KeyValueDiff()
        l = ['a', 'b', 'c']
        r = ['b', 'd', 'c']
        for l, r in map(None, l, r):
            list_diff.append(l, r)
        self.assertEqual(list_diff.diff(), ['-[a]', '+[d]'])

    def test_key_value_diff_with_common_elements_and_sort_issue(self):
        list_diff = KeyValueDiff()
        l = ['a', 'b', 'c']
        r = ['c', 'd', 'b']
        for l, r in map(None, l, r):
            list_diff.append(l, r)
        self.assertEqual(
                list_diff.diff(),
                ['-[a]',
                 '+[d]',
                 'Only order of remaining entries differs: [b, c] vs [c, b].'])

    def test_key_value_diff_with_common_elements_repetitions(self):
        list_diff = KeyValueDiff()
        l = ['a', 'b', 'b', 'c']
        r = ['c', 'b', 'b', 'b']
        for l, r in map(None, l, r):
            list_diff.append(l, r)
        self.assertEqual(
                list_diff.diff(),
                ['-[a]',
                 'Order and repetition count of remaining entries differs: ' +
                 '[b, c] vs [c, b, b].'])

    def test_key_value_diff_sort(self):
        list_diff = KeyValueDiff()
        list_diff.append('1', '2')
        list_diff.append('2', '1')
        self.assertEqual(
            list_diff.diff(),
            ["Only order of entries differs: [1, 2] vs [2, 1]."])

    def test_key_value_diff_case(self):
        list_diff = KeyValueDiff()
        list_diff.append('B', 'a')
        list_diff.append('a', 'b')
        self.assertEqual(
            list_diff.diff(),
            ["Only order and letter casing (Upper Case vs lower case) of " +
             "entries differs:", '-[B]', '+[b]'])

    def test_key_value_diff_paths(self):
        list_diff = KeyValueDiff()
        list_diff.append('path(a.java:123)', 'path(a.java:322)')
        list_diff.append('path(C.java:123)', 'path(c.java:123)')
        list_diff.diff()
        self.assertEqual(
                set(list_diff.getInterestingPaths()),
                set(['a.java', 'c.java', 'C.java']))

    def test_structure_info(self):
        line = ("[v] RuleKey 00aa=string(\"//:rule\"):key(name):" +
                "number(1):key(version):string(\"Rule\"):key(buck.type):")
        info = RuleKeyStructureInfo(MockFile([line]))
        self.assertEqual(info.getNameForKey("00aa"), "//:rule")

    def test_structure_info_list(self):
        line = ("[v] RuleKey 00aa=string(\"//:rule\"):key(name):" +
                "number(1):key(version):string(\"Rule\"):key(buck.type):" +
                "number(1):key(num):number(2):key(num):")
        info = RuleKeyStructureInfo(MockFile([line]))
        self.assertEqual(
                info.getByKey("00aa")['num'],
                ["number(2)", "number(1)"])

    def test_simple_diff(self):
        name = "//:lib"
        result = diff(name,
                      RuleKeyStructureInfo(MockFile([
                          makeRuleKeyLine(
                              name=name,
                              key="aabb",
                              srcs={'JavaLib1.java': 'aabb'}
                          ),
                      ])),
                      RuleKeyStructureInfo(MockFile([
                          makeRuleKeyLine(
                              name=name,
                              key="cabb",
                              srcs={'JavaLib1.java': 'cabb'}
                          ),
                      ])),
                      verbose=False)

        expected = [
                'Change details for [//:lib]',
                '  (srcs):',
                '    -[path(JavaLib1.java:aabb)]',
                '    +[path(JavaLib1.java:cabb)]']
        self.assertEqual(result, expected)

    def test_diff_deps_order(self):
        result = diff("//:top",
                      RuleKeyStructureInfo(MockFile([
                          makeRuleKeyLine(
                              name="//:top",
                              key="aa",
                              deps=["00", "10"],
                          ),
                          makeRuleKeyLine(
                              name="//:Zero",
                              key="00",
                              srcs={"Zero": "0"}
                          ),
                          makeRuleKeyLine(
                              name="//:One",
                              key="10",
                              srcs={"One": "0"}
                          ),
                      ])),
                      RuleKeyStructureInfo(MockFile([
                          makeRuleKeyLine(
                              name="//:top",
                              key="bb",
                              deps=["11", "01"],
                          ),
                          makeRuleKeyLine(
                              name="//:Zero",
                              key="01",
                              srcs={"Zero": "0"}
                          ),
                          makeRuleKeyLine(
                              name="//:One",
                              key="11",
                              srcs={"One": "1"}
                          ),
                      ])),
                      verbose=False)
        expected = [
                'Change details for [//:top]',
                '  (deps): order of deps was name-aligned.',
                'Change details for [//:One]',
                '  (srcs):',
                '    -[path(One:0)]',
                '    +[path(One:1)]',
        ]
        self.assertEqual(result, expected)

    def test_diff_deps_count(self):
        result = diff("//:top",
                      RuleKeyStructureInfo(MockFile([
                          makeRuleKeyLine(
                              name="//:top",
                              key="aa",
                              deps=["00"],
                          ),
                          makeRuleKeyLine(
                              name="//:Zero",
                              key="00",
                              srcs={"Zero": "0"}
                          ),
                      ])),
                      RuleKeyStructureInfo(MockFile([
                          makeRuleKeyLine(
                              name="//:top",
                              key="bb",
                              deps=["11", "01"],
                          ),
                          makeRuleKeyLine(
                              name="//:Zero",
                              key="01",
                              srcs={"Zero": "0"}
                          ),
                          makeRuleKeyLine(
                              name="//:One",
                              key="11",
                              srcs={"One": "1"}
                          ),
                      ])),
                      verbose=False)
        expected = [
                'Change details for [//:top]',
                '  (deps):',
                '    -[<missing>]',
                '    +["//:One"@ruleKey(sha1=11)]',
        ]
        self.assertEqual(result, expected)


    def test_simple_diff_with_custom_names(self):
        line = ("[v] RuleKey {key}=string(\"//:lib\"):key(name):" +
                "path(JavaLib1.java:{hash}):key(srcs):" +
                "string(\"t\"):key(buck.type):")
        left_line = line.format(key="aabb", hash="ll")
        right_line = line.format(key="aabb", hash="rr")
        result = diff("//:lib",
                      RuleKeyStructureInfo(MockFile([left_line])),
                      RuleKeyStructureInfo(MockFile([right_line])),
                      verbose=False,
                      format_tuple=('l(%s)', 'r{%s}'),
                      check_paths=True)
        expected = [
                'Change details for [//:lib]',
                '  (srcs):',
                '    l(path(JavaLib1.java:ll))',
                '    r{path(JavaLib1.java:rr)}',
                'Information on paths the script has seen:',
                ' JavaLib1.java does not exist']
        self.assertEqual(result, expected)

    def test_length_diff(self):
        line = ("[v] RuleKey {key}=string(\"//:lib\"):key(name):" +
                "{srcs}:" +
                "string(\"t\"):key(buck.type):")
        left_srcs = ["path(%s):key(srcs)" % p for p in ['a:1', 'b:2', 'c:3']]
        left_line = line.format(key="aabb", srcs=":".join(left_srcs))
        right_srcs = left_srcs[:-1]
        right_line = line.format(key="axbb", srcs=":".join(right_srcs))
        result = diff("//:lib",
                      RuleKeyStructureInfo(MockFile([left_line])),
                      RuleKeyStructureInfo(MockFile([right_line])),
                      verbose=False)
        expected = [
                'Change details for [//:lib]',
                '  (srcs):',
                '    -[path(c:3)]',
                '    +[<missing>]']
        self.assertEqual(result, expected)

    def test_interesting_path_report(self):
        temp_file = None
        try:
            temp_file = tempfile.NamedTemporaryFile(delete=False)
            temp_file.close()

            dirpath = os.getcwd()
            filepath = temp_file.name

            empty_hash = 'da39a3ee5e6b4b0d3255bfef95601890afd80709'
            self.assertEqual(
                    reportOnInterestingPaths([dirpath, filepath]),
                    [' %s is not a file' % dirpath,
                     ' %s exists and hashes to %s' % (filepath, empty_hash)])

            self.assertEqual(
                    reportOnInterestingPaths(['no/suchfile', dirpath]),
                    [' no/suchfile does not exist',
                     ' %s is not a file' % dirpath])

        finally:
            if temp_file is not None:
                os.unlink(temp_file.name)


def makeRuleKeyLine(key="aabb", name="//:name", srcs=None,
                    ruleType="java_library", deps=None):
    srcs = srcs or {'JavaLib1.java': 'aabb'}
    deps = deps or []
    srcs_t = ":".join(['path({p}:{h}):key(srcs)'.format(p=p, h=h)
                       for p, h in srcs.iteritems()])
    deps_t = ":".join(['ruleKey(sha1={h}):key(deps)'.format(h=h)
                       for h in deps])
    template = ("[v] RuleKey {key}=string(\"{name}\"):key(name):" +
                "{srcs_t}:"
                "string(\"{ruleType}\"):key(buck.type):" +
                "{deps_t}:")
    return template.format(key=key, name=name, srcs_t=srcs_t,
                           ruleType=ruleType, deps_t=deps_t)

if __name__ == '__main__':
    unittest.main()
