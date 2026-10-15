"""Analytic checks independent of the Core's float-dot scalar diagnostic."""
import importlib.util,math,unittest
from pathlib import Path
spec=importlib.util.spec_from_file_location('audit',Path(__file__).with_name('analyze-angular.py'))
audit=importlib.util.module_from_spec(spec);spec.loader.exec_module(audit)

class AngularAuditTests(unittest.TestCase):
    def test_antipodal_and_scaled_quaternions_are_equivalent(self):
        q=[.1,.2,-.3,.9]
        self.assertLess(audit.derivative(q,[-7*x for x in q],.017)['angularSpeedRadPerSec'],1e-12)

    def test_actual_irregular_dt_recovers_analytic_constant_rate(self):
        previous=[0,0,0,1];time=0
        for dt in [.017,.029,.008,.071]:
            time+=dt;q=[0,math.sin(1.3*time/2),0,math.cos(1.3*time/2)]
            result=audit.derivative(previous,q,dt)
            self.assertAlmostEqual(result['angularSpeedRadPerSec'],1.3,places=12)
            self.assertAlmostEqual(result['worldAngularVelocityRadPerSec'][1],1.3,places=12)
            previous=q

    def test_wrap_uses_shortest_arc(self):
        def yaw(angle):return [0,math.sin(angle/2),0,math.cos(angle/2)]
        result=audit.derivative(yaw(math.radians(179)),yaw(math.radians(-179)),.02)
        self.assertAlmostEqual(result['angleRadians'],math.radians(2),places=12)
        self.assertTrue(result['hemisphereFlip'])

    def test_invalid_dt_retains_angle_without_inventing_velocity(self):
        for dt in [0,-.01,None]:
            result=audit.derivative([0,0,0,1],[0,math.sin(.2),0,math.cos(.2)],dt)
            self.assertAlmostEqual(result['angleRadians'],.4)
            self.assertIsNone(result['angularSpeedRadPerSec'])

    def test_world_frame_rotation_is_independent_of_body_axes(self):
        previous=[0,0,math.sin(.7),math.cos(.7)]
        delta=[math.sin(.02),0,0,math.cos(.02)]
        current=audit.mul(delta,previous)
        result=audit.derivative(previous,current,.02)
        self.assertAlmostEqual(result['worldAngularVelocityRadPerSec'][0],2)
        self.assertAlmostEqual(result['worldAngularVelocityRadPerSec'][1],0)
        self.assertAlmostEqual(result['worldAngularVelocityRadPerSec'][2],0)

    def test_invalid_quaternion_is_explicit_error(self):
        for q in [[0,0,0,0],[0,float('nan'),0,1]]:
            with self.assertRaises(ValueError):audit.derivative(q,[0,0,0,1],.02)

if __name__=='__main__':unittest.main()
