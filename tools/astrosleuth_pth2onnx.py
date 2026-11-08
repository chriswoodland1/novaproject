import sys, zipfile, pickle, numpy as np, onnx
from onnx import helper, TensorProto, numpy_helper
src, dst = sys.argv[1], sys.argv[2]
z = zipfile.ZipFile(src)
names = z.namelist(); root = names[0].split('/')[0]
dtypes = {'FloatStorage': np.float32, 'HalfStorage': np.float16, 'LongStorage': np.int64}
class U(pickle.Unpickler):
    def find_class(self, mod, name):
        if name == '_rebuild_tensor_v2':
            def rb(storage, offset, size, stride, *a):
                arr, = storage
                n = int(np.prod(size)) if len(size) else 1
                return np.lib.stride_tricks.as_strided(arr[offset:], shape=size, strides=[s * arr.itemsize for s in stride]).copy() if len(size) else arr[offset:offset+1].copy()
            return rb
        if name == 'OrderedDict':
            import collections; return collections.OrderedDict
        if mod.startswith('torch') and name.endswith('Storage'):
            return name
        return super().find_class(mod, name)
    def persistent_load(self, pid):
        _, stype, key, loc, numel = pid
        data = z.read(f'{root}/data/{key}')
        return (np.frombuffer(data, dtype=dtypes[stype if isinstance(stype, str) else stype]),)
sd = U(z.open(f'{root}/data.pkl')).load()
if 'params' in sd: sd = sd['params']
if 'state_dict' in sd: sd = sd['state_dict']
keys = list(sd.keys()); print(len(keys), keys[:6], keys[-6:])
nodes = []; inits = []
def W(name, arr): inits.append(numpy_helper.from_array(np.asarray(arr, np.float32), name)); return name
x = 'data'; cur = x; i = 0; layer = 0
body = sorted({int(k.split('.')[1]) for k in keys if k.startswith('body.')})
for idx in body:
    if f'body.{idx}.weight' in sd and sd[f'body.{idx}.weight'].ndim == 4:
        w = W(f'w{idx}', sd[f'body.{idx}.weight']); b = W(f'b{idx}', sd[f'body.{idx}.bias'])
        out = f'c{idx}'; nodes.append(helper.make_node('Conv', [cur, w, b], [out], pads=[1,1,1,1], kernel_shape=[3,3])); cur = out
    else:
        sl = W(f'p{idx}', sd[f'body.{idx}.weight'].reshape(-1, 1, 1))
        out = f'r{idx}'; nodes.append(helper.make_node('PRelu', [cur, sl], [out])); cur = out
scales = W('sc2', np.array([1, 1, 2, 2], np.float32)); scales4 = W('sc4', np.array([1, 1, 4, 4], np.float32)); roi = W('roi', np.array([], np.float32))
def up(inp, out, sc): nodes.append(helper.make_node('Resize', [inp, roi, sc], [out], mode='linear', coordinate_transformation_mode='pytorch_half_pixel'))
up(cur, 'u1', scales)
nodes.append(helper.make_node('Conv', ['u1', W('wu1', sd['conv_up1.weight']), W('bu1', sd['conv_up1.bias'])], ['cu1'], pads=[1,1,1,1], kernel_shape=[3,3]))
nodes.append(helper.make_node('LeakyRelu', ['cu1'], ['lu1'], alpha=0.2))
up('lu1', 'u2', scales)
nodes.append(helper.make_node('Conv', ['u2', W('wu2', sd['conv_up2.weight']), W('bu2', sd['conv_up2.bias'])], ['cu2'], pads=[1,1,1,1], kernel_shape=[3,3]))
up(x, 'ux', scales4)
nodes.append(helper.make_node('Add', ['cu2', 'ux'], ['output']))
g = helper.make_graph(nodes, 'AstroSleuthFAST',
    [helper.make_tensor_value_info('data', TensorProto.FLOAT, ['N', 3, 'H', 'W'])],
    [helper.make_tensor_value_info('output', TensorProto.FLOAT, ['N', 3, 'H4', 'W4'])], inits)
m = helper.make_model(g, opset_imports=[helper.make_opsetid('', 13)]); m.ir_version = 8
onnx.checker.check_model(m); onnx.save(m, dst); print('saved', dst)
