import copy
import unittest
from ownership import validate

class OwnershipTests(unittest.TestCase):
    def setUp(self):
        self.manifest = {'initial':'all-zero','namespaceSha256':'trusted-hash',
            'owners':[{'uid':'exact-uid','email':'synthetic-a@example.invalid'}],
            'documents':['users/exact-uid','users/exact-uid/lists/fb709-list'],
            'photos':['users/exact-uid/items/fb709-item/fb709-upload']}
        self.accounts = [{'localId':'exact-uid','email':'SYNTHETIC-A@example.invalid'}]
    def check(self, manifest=None, namespace='trusted-hash', accounts=None, documents=None, photos=None):
        validate(manifest or self.manifest, namespace, self.accounts if accounts is None else accounts,
            self.manifest['documents'] if documents is None else documents,
            self.manifest['photos'] if photos is None else photos)
    def test_normalized_email_requires_exact_uid(self): self.check()
    def test_same_email_cannot_authorize_foreign_uid(self):
        with self.assertRaises(ValueError): self.check(accounts=[{'localId':'foreign','email':'synthetic-a@example.invalid'}])
    def test_exact_uid_cannot_authorize_foreign_email(self):
        with self.assertRaises(ValueError): self.check(accounts=[{'localId':'exact-uid','email':'foreign@example.invalid'}])
    def test_extra_account_refuses_all_deletion(self):
        with self.assertRaises(ValueError): self.check(accounts=self.accounts+[{'localId':'foreign','email':'foreign@example.invalid'}])
    def test_foreign_namespace_refuses_all_deletion(self):
        with self.assertRaises(ValueError): self.check(namespace='foreign-project')
    def test_unlisted_document_refuses_all_deletion(self):
        with self.assertRaises(ValueError): self.check(documents=['users/exact-uid/lists/unlisted'])
    def test_unlisted_photo_refuses_all_deletion(self):
        with self.assertRaises(ValueError): self.check(photos=['users/exact-uid/items/fb709-item/unlisted'])
    def test_untrusted_initial_inventory_is_rejected(self):
        m=copy.deepcopy(self.manifest);m['initial']='unknown'
        with self.assertRaises(ValueError): self.check(manifest=m)

if __name__ == '__main__': unittest.main()
