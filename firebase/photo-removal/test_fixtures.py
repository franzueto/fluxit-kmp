"""Verify local cleanup HTTP methods and routing without network or deletion."""
import ast
import io
import json
from pathlib import Path
import unittest
from unittest.mock import patch
import urllib.error
import urllib.parse
import urllib.request

source = Path(__file__).with_name('fixtures.py').read_text()
function = next(node for node in ast.parse(source).body
                if isinstance(node, ast.FunctionDef) and node.name == 'request')
scope = {'json': json, 'urllib': urllib}
exec(compile(ast.Module(body=[function], type_ignores=[]), 'fixtures.py', 'exec'), scope)
request = scope['request']


class FixtureRequestTest(unittest.TestCase):
    def test_delete_reaches_transport_as_delete(self):
        with patch('urllib.request.urlopen', return_value=io.BytesIO(b'{}')) as transport:
            request('http://127.0.0.1:8080/v1/synthetic-document', method='DELETE')
        self.assertEqual('DELETE', transport.call_args.args[0].get_method())

    def test_auth_body_reaches_transport_as_post(self):
        with patch('urllib.request.urlopen', return_value=io.BytesIO(b'{}')) as transport:
            request('http://127.0.0.1:9099/synthetic-account', {'localId': 'synthetic-owner'})
        self.assertEqual('POST', transport.call_args.args[0].get_method())

    def test_read_reaches_transport_as_get(self):
        with patch('urllib.request.urlopen', return_value=io.BytesIO(b'{}')) as transport:
            request('http://127.0.0.1:9199/synthetic-bucket')
        self.assertEqual('GET', transport.call_args.args[0].get_method())

    def test_non_loopback_routes_are_rejected_before_transport(self):
        with patch('urllib.request.urlopen') as transport:
            for url in ['https://firestore.googleapis.com/v1/synthetic',
                        'http://localhost:8080/synthetic', 'http://127.0.0.1:443/synthetic']:
                with self.assertRaises(AssertionError):
                    request(url, method='DELETE')
        transport.assert_not_called()


if __name__ == '__main__':
    unittest.main()
