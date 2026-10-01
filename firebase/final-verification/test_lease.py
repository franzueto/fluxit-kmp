import copy
import unittest
from lease import all_zero,validate_armed,EXPECTED_ENDPOINTS
class LeaseTests(unittest.TestCase):
    def good(self):
        return {'task':'FB-706','policy':'PLAN-011','unexported':True,'initialAllZero':True,'endpoints':EXPECTED_ENDPOINTS.copy(),'pid':123,'processGroup':123,'initialInventorySha256':'a'*64,'artifacts':{'a':'b'*64,'b':'c'*64,'c':'d'*64},'fixtureSources':{'fixture':'e'*64}}
    def test_fully_armed_lease(self):validate_armed(self.good())
    def test_nonempty_inventory_rejected(self):
        self.assertTrue(all_zero({'native':{'roots':[],'lists':[],'items':[],'accounts':[]},'photos':{'bucket':[]}}))
        for field in ['roots','lists','items','accounts']:
            self.assertFalse(all_zero({'native':{field:['foreign']},'photos':{'bucket':[]}}))
        self.assertFalse(all_zero({'native':{'roots':[]},'photos':{'bucket':['foreign']}}))
    def test_remote_or_foreign_endpoints_rejected(self):
        value=self.good();value['endpoints']['firestore']='example.com:8080'
        with self.assertRaises(AssertionError):validate_armed(value)
    def test_unarmed_or_exported_lease_rejected(self):
        for field in ['unexported','initialAllZero']:
            value=self.good();value[field]=False
            with self.assertRaises(AssertionError):validate_armed(value)
    def test_foreign_process_group_rejected(self):
        value=self.good();value['processGroup']=456
        with self.assertRaises(AssertionError):validate_armed(value)
    def test_missing_artifact_or_source_proof_rejected(self):
        for field in ['artifacts','fixtureSources']:
            value=self.good();value[field]={}
            with self.assertRaises(AssertionError):validate_armed(value)
if __name__=='__main__':unittest.main()
