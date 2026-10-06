"""Pure exact-manifest checks shared by native fixture cleanup and interruption recovery."""
def validate(manifest, namespace_hash, accounts, document_paths, photo_paths):
    if manifest.get('initial') != 'all-zero' or manifest.get('namespaceSha256') != namespace_hash:
        raise ValueError('untrusted fixture namespace')
    known = {o['uid']: o['email'].casefold() for o in manifest['owners']}
    if len(known) != len(manifest['owners']):
        raise ValueError('duplicate manifest owner')
    for account in accounts:
        if account['localId'] not in known or account.get('email', '').casefold() != known[account['localId']]:
            raise ValueError('foreign account')
    if not set(document_paths) <= set(manifest['documents']):
        raise ValueError('foreign document path')
    if not set(photo_paths) <= set(manifest['photos']):
        raise ValueError('foreign photo path')
