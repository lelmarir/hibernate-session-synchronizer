package com.example;

import java.util.Set;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.EntityTransaction;
import javax.persistence.Persistence;

public class App {

	public static void main(String[] args) {

		EntityManagerFactory emf = Persistence.createEntityManagerFactory("example");
		EntityManager em = emf.createEntityManager();

		EntityTransaction tx = em.getTransaction();
		// init
		Integer id = null;
		tx.begin();
		try {

			Foo foo = new Foo();
			Set<Bar> bars = foo.getBars();
			bars.add(new Bar());
			bars.add(new Bar());
			bars.add(new Bar());

			em.persist(foo);

			id = foo.id;
			System.out.println(foo);

			tx.commit();
		} catch (Exception e) {
			tx.rollback();
		}
		em.close();

		// concurrent access
		em = emf.createEntityManager();
		tx = em.getTransaction();
		tx.begin();
		try {
			System.out.println("start");
			Foo foo = em.find(Foo.class, id);
			System.out.println(foo.id);
			// foo.getBars() is still not loaded

			new Thread(() -> {
				System.out.println("other thread start");
				EntityManager em2 = emf.createEntityManager();
				EntityTransaction tx2 = em2.getTransaction();
				tx2.begin();
				try {
					System.out.println("other thread tx start");
					// foo.getBars() is still not loaded, so it is a PersistentCollection, attached
					// to the EntityManager of the other thread, accessing getBars() will cause a
					// lazy loading that may cause some exceptions in the EntityManager (non
					// thread-safe)
					Set<Bar> bars = foo.getBars();
					System.out.println(bars);

					tx2.commit();
				} catch (Exception e) {
					e.printStackTrace();
					tx2.rollback();
				}
				System.out.println("other thread tx end");
				em2.close();
				System.out.println("other thread end");
			}).start();

			// sleep to keep the transaction and em active, so the other thread can run
			// concurrently
			Thread.sleep(2000);

			tx.commit();
		} catch (Exception e) {
			e.printStackTrace();
			tx.rollback();
		}

		em.close();
		System.out.println("end");
	}

}
