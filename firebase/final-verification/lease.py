"""Pure fail-closed validation for the PLAN-011 isolated local test lease."""
EXPECTED_ENDPOINTS={'auth':'127.0.0.1:9099','firestore':'127.0.0.1:8080','storage':'127.0.0.1:9199'}
def all_zero(inventory):
    return all(not rows for values in inventory.values() for rows in values.values())
def validate_armed(value):
    assert value['task']=='FB-706' and value['policy']=='PLAN-011'
    assert value['unexported'] is True and value['initialAllZero'] is True
    assert value['endpoints']==EXPECTED_ENDPOINTS
    assert isinstance(value['pid'],int) and value['pid']>0 and value['processGroup']==value['pid']
    assert len(value['initialInventorySha256'])==64
    assert len(value['artifacts'])>=3 and all(len(d)==64 for d in value['artifacts'].values())
    assert value['fixtureSources'] and all(len(d)==64 for d in value['fixtureSources'].values())
